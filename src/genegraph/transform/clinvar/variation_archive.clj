(ns genegraph.transform.clinvar.variation-archive
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-to-model
                                                        variation-geno-type
                                                        genegraph-kw-to-iri
                                                        json-prettify
                                                        mark-prior-replaced
                                                        clinvar-model-to-jsonld
                                                        model-framed-to-jsonld]]
            [genegraph.transform.clinvar.iri :as iri]
            [clojure.pprint :refer [pprint]]
            [clojure.datafy :refer [datafy]]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [io.pedestal.log :as log])
  (:import (org.apache.jena.rdf.model Model)))

(def prefix-vrs-1-2-0 "https://vrs.ga4gh.org/en/1.2.0/")
(defn ns-vrs [term] (str prefix-vrs-1-2-0 term))
(defn ns-cg [term] (str iri/cgterms term))

(def variation-archive-frame
  "Frame map for VCV"
  {;"@context" {"@vocab" iri/cgterms}
   ;"@type" (ns-cg "ClinVarVCVStatement")
   "@type" "http://dataexchange.clinicalgenome.org/terms/ClinVarVCVStatement"
   })

; test data load
(defn test-fn-titanium []
  (let [kafka-messages (-> "vcv-messages.txt" io/file slurp (#(s/split % #"\n")) (->> (map #(json/parse-string % true))))
        triples (-> kafka-messages first ((eval 'variation-archive-v1)))
        model ^Model (l/statements-to-model triples)]
    (model-framed-to-jsonld model variation-archive-frame)))


; TODO
; make VariationRuleDescriptor
; add fields to VariationDescriptor
; promote xrefs to Resource graphql type

; TODO This should be in the variation transformer, not variation archive
(defn vrs-allele-for-variation [variation]
  ())

(defn variation-archive-v1-triples [msg]
  (let [msg (assoc-in msg [:content :release_date] (:release_date msg))
        msg (assoc-in msg [:content :event_type] (:event_type msg))
        msg (:content msg)

        vcv-iri (str iri/variation-archive (:id msg))
        vcv-statement-unversioned-iri (str vcv-iri "_statement")
        vcv-statement-iri (str vcv-statement-unversioned-iri "." (:release_date msg))
        clinvar-variation-iri (q/resource (str iri/clinvar-variation (:variation_id msg)))
        ;proposition-iri (l/blank-node)
        proposition-iri (q/resource (str vcv-statement-unversioned-iri "_proposition." (:release_date msg)))
        variation-rule-descriptor-iri (q/resource (str vcv-statement-unversioned-iri "_variation_rule_descriptor." (:release_date msg)))]
    (concat
      [; SEPIO Statement (ClinVarVCVStatement)
       ; statement: <proposition> <has confidence + direction> <strength>
       [vcv-statement-iri :rdf/type :sepio/Statement]
       [vcv-statement-iri :rdf/type (q/resource (ns-cg "ClinVarVCVStatement"))]
       [vcv-statement-iri :rdf/type (q/resource (ns-cg "ClinVarObject"))] ; For tracking clinvar objects
       [vcv-statement-iri :dc/has-version (:version msg)]
       [vcv-statement-iri :dc/is-version-of (q/resource (str iri/variation-archive (:id msg)))]
       [vcv-statement-iri :cg/release-date (:release_date msg)]

       [vcv-statement-iri :sepio/has-predicate (q/resource (ns-cg "has_evidence_level"))]
       ; TODO change to boolean literal. Requires adding impl to AsResource
       [vcv-statement-iri :cg/negated "FALSE"]
       [vcv-statement-iri :sepio/has-object (:review_status msg)] ; ex: "criteria provided, conflicting interpretations"


       ; VCV Statement subject (unversioned Variation/Allele. Basic info, not making a whole variation doc here)
       ;[vcv-statement-iri :sepio/has-subject proposition-iri]
       ;[subject-iri :rdf/type (ns-vrs "Allele")]


       ; SEPIO Proposition (for VCV statement, ClinVarVCVProposition)
       ; proposition: <variation> <has classification> <vcv interpretation>
       [vcv-statement-iri :sepio/has-subject proposition-iri]
       [proposition-iri :rdf/type :sepio/Proposition]
       [proposition-iri :rdf/type (q/resource (ns-cg "ClinVarVCVProposition"))]
       [proposition-iri :sepio/has-subject variation-rule-descriptor-iri]
       [proposition-iri :sepio/has-predicate (q/resource (ns-cg "has_clinvar_variant_aggregate_classification"))]
       [proposition-iri :sepio/has-object (:interp_description msg)] ; ex: "Conflicting interpretations of pathogenicity"


       ; Variation Rule Descriptor
       [variation-rule-descriptor-iri :rdf/type (q/resource (ns-cg "VariationRuleDescriptor"))]
       [variation-rule-descriptor-iri :vrs/xref clinvar-variation-iri]]
      ; TODO Label (variant name?) should be added to this same VariationRuleDescriptor when received from variation record

      ; Extensions
      (let [leftover (dissoc msg :id :release_date :version :review_status :interp_description)
            extensions (into []
                             ; flatten one level
                             (apply concat
                                    (map (fn [[k v]]
                                           (let [ext-iri (l/blank-node)]
                                             [[vcv-statement-iri :vrs/extension ext-iri]
                                              [ext-iri :rdf/type :vrs/Extension]
                                              [ext-iri :vrs/name (name k)]
                                              [ext-iri :vrs/value v]]))
                                         leftover)))]
        (log/trace :extensions extensions)
        extensions)
      )))

(defn resource-to-out-triples
  "Uses steppable interface of RDFResource to obtain all the out properties and load
  them into a Model. These triples can be used as input to l/statements-to-model.
  NOTE: that only works when all the properties of the resource are in property-names.edn"
  [resource]
  ; [k v] -> [r k v]
  (map #(cons resource %) (into {} resource)))

(defmethod clinvar-to-model :variation_archive [event]
  (log/debug :fn ::clinvar-to-model :event event)
  (let [model (-> event
                  :genegraph.transform.clinvar.core/parsed-value
                  variation-archive-v1-triples
                  (#(do (log/info :triples %) %))
                  l/statements-to-model
                  (#(do (log/info :model %) %))
                  (#(mark-prior-replaced % (q/resource (ns-cg "ClinVarVCVStatement")))))]
    (log/debug :fn ::clinvar-to-model :model model)
    model))

(defmethod clinvar-model-to-jsonld :variation_archive [event]
  (let [model (::q/model event)]
    (model-framed-to-jsonld model variation-archive-frame)))
