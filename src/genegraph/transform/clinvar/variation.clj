(ns genegraph.transform.clinvar.variation
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.database.names :refer [local-property-names
                                              property-uri->keyword]]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [genegraph.transform.jsonld.common :as jsonld]
            [clojure.pprint :refer [pprint]]
            [clojure.datafy :refer [datafy]]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [io.pedestal.log :as log]
            [genegraph.transform.clinvar.util :as util])
  (:import (org.apache.jena.rdf.model Model)))

(def clinvar-variation-type (ns-cg "ClinVarVariation"))
(def variation-frame
  "Frame map for variation"
  {"@type" clinvar-variation-type})


; TODO
; make VariationRuleDescriptor
; add fields to VariationDescriptor
; promote xrefs to Resource graphql type
;(defn ^String get-canonical-representation-for-variation
;  [variation]
;  (let [nested-content (json/parse-string (:content variation))
;        spdi (get nested-content "CanonicalSPDI")]
;    (if spdi
;      spdi
;      (let [hgvs-list (get nested-content "HGVSlist")]))))
(defn prioritized-variation-expression
  "Attempts to return a 'canonical' expression of the variation in ClinVar.
  Takes a clinvar-raw variation message. Returns one string expression.
  Tries GRCh38, GRCh37, may add additional options in the future.

  NOTE: .content.content should be parsed already, but it tries to parse it again if not."
  [variation-msg]
  (let [hgvs-list (-> variation-msg (util/parse-nested-content) (get "HGVSlist") (get "HGVS"))]
    (let [matchers {:GRCh38 #(= "GRCh38" (get-in % ["NucleotideExpression" "@Assembly"]))
                    :GRCh37 #(= "GRCh37" (get-in % ["NucleotideExpression" "@Assembly"]))}
          getters {:GRCh38 #(-> % (get "NucleotideExpression") (get "Expression") (get "$"))
                   :GRCh37 #(-> % (get "NucleotideExpression") (get "Expression") (get "$"))}
          get-hgvs (fn [hgvs-list matcher-key]
                     (let [filtered (filter (get matchers matcher-key) hgvs-list)]
                       (if (< 1 (count filtered)) (log/warn :fn ::prioritized-variation-expression
                                                            :msg (str "Multiple expressions for variation for type " matcher-key)
                                                            :variation variation-msg))
                       (-> filtered first ((get getters matcher-key)))))]
      (let [exprs (for [mk [:GRCh38 :GRCh37]]
                    (get-hgvs hgvs-list mk))]
        ; Return first non-nil
        (some identity exprs)))))


(defn variation-triples [msg]
  (let [msg (assoc-in msg [:content :release_date] (:release_date msg))
        msg (assoc-in msg [:content :event_type] (:event_type msg))
        msg (:content msg)

        vrd-unversioned (str (ns-cg "VariationDescriptor_") (:id msg))
        vrd-versioned (str vrd-unversioned "." (:release_date msg))
        ;variation-rule-descriptor-iri (q/resource (str vcv-statement-unversioned-iri "_variation_rule_descriptor." (:release_date msg)))]
        clinvar-variation-iri (q/resource (str iri/clinvar-variation (:id msg)))]
    (concat
      [; VRS Variation Rule Descriptor
       ;[vrd-versioned :rdf/type (ccommon/variation-geno-type (:subclass_type msg))]
       ;[vrd-versioned :rdf/type (ccommon/variation-vrs-type (:subclass_type msg))]
       [vrd-versioned :rdf/type :vrs/VariationDescriptor]
       [vrd-versioned :rdf/type (q/resource (ns-cg "ClinVarVariation"))]
       ; For tracking clinvar objects and identifying the named graph
       [vrd-versioned :rdf/type (q/resource (ns-cg "ClinVarObject"))]

       ; Rule Descriptor describes object: variation
       [vrd-versioned :sepio/has-object clinvar-variation-iri]
       [vrd-versioned :dc/is-version-of (q/resource vrd-unversioned)]
       [vrd-versioned :cg/release-date (:release_date msg)]

       ;(q/resource (str iri/clinvar-variation (:variation_id msg)))
       ; TODO reverse link to VCV? Or rely on VCV->variation that should be added by VCV
       ;;[subject-iri :rdf/type (ns-vrs "Allele")]

       ;; Variation Rule Descriptor
       ;[variation-rule-descriptor-iri :rdf/type (q/resource (ns-cg "VariationRuleDescriptor"))]
       ;[variation-rule-descriptor-iri :vrs/xref clinvar-variation-iri]]
       ]
      ; TODO Label (variant name?) should be added to this same VariationRuleDescriptor when received from variation record

      ; Extensions
      (common/fields-to-extensions vrd-versioned (dissoc msg :id :release_date)))))

(defn resource-to-out-triples
  "Uses steppable interface of RDFResource to obtain all the out properties and load
  them into a Model. These triples can be used as input to l/statements-to-model.
  NOTE: that only works when all the properties of the resource are in property-names.edn"
  [resource]
  ; [k v] -> [r k v]
  (map #(cons resource %) (into {} resource)))

(defmethod common/clinvar-to-model :variation [event]
  (log/debug :fn ::clinvar-to-model :event event)
  (let [model (-> event
                  :genegraph.transform.clinvar.core/parsed-value
                  variation-triples
                  (#(do (log/info :triples %) %))
                  l/statements-to-model
                  (#(do (log/info :model %) %))
                  (#(common/mark-prior-replaced % (q/resource clinvar-variation-type))))]
    (log/debug :fn ::clinvar-to-model :model model)
    model))

(def variation-context
  {"@context" {"object" {"@id" (str (get local-property-names :sepio/has-object))
                         "@type" "@id"}
               "is_version_of" {"@id" (str (get local-property-names :dc/is-version-of))
                                "@type" "@id"}
               "type" {"@id" "@type"
                       "@type" "@id"}
               "release_date" {"@id" (str (get local-property-names :cg/release-date))}
               "extension" {"@id" (str (get local-property-names :vrs/extension))}
               "name" {"@id" (str (get local-property-names :vrs/name))}
               "value" {"@id" (str (get local-property-names :vrs/value))}
               }})


(defmethod common/clinvar-model-to-jsonld :variation [event]
  (let [model (::q/model event)]
    (-> model
        (jsonld/model-to-jsonld)
        (jsonld/jsonld-to-jsonld-framed (json/generate-string variation-frame))
        (jsonld/jsonld-compact (json/generate-string variation-context)))))
