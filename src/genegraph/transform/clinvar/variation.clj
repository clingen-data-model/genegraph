(ns genegraph.transform.clinvar.variation
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :as ccommon]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [clojure.pprint :refer [pprint]]
            [clojure.datafy :refer [datafy]]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [io.pedestal.log :as log])
  (:import (org.apache.jena.rdf.model Model)))


(def clinvar-variation-type (ns-cg "ClinVarVariation"))
(def variation-frame
  "Frame map for variation"
  {;"@context" {"@vocab" iri/cgterms}
   "@type" clinvar-variation-type})


; TODO
; make VariationRuleDescriptor
; add fields to VariationDescriptor
; promote xrefs to Resource graphql type

;(def cancer-variants-normalize-url
;  "URL for cancervariants.org VRSATILE normalization.
;  Returns a JSON document containing a variation_descriptor field along with other metadata."
;  "https://normalize.cancervariants.org/variation/normalize")
;
;(defn ^String get-canonical-representation-for-variation
;  [variation]
;  (let [nested-content (json/parse-string (:content variation))
;        spdi (get nested-content "CanonicalSPDI")]
;    (if spdi
;      spdi
;      (let [hgvs-list (get nested-content "HGVSlist")
;            ]))))
;
;(defn vrs-allele-for-variation
;  "`variation` should be a string understood by the VICC normalization service.
;  https://normalize.cancervariants.org/variation"
;  [variation]
;  (let [response (http/get cancer-variants-normalize-url {:query-params {"q" "NG_011645.1:g.58868C>T"}})
;        status (:status response)]
;    (case status
;      200 (let [body (:body response)]
;            (log/info :fn ::vrs-allele-for-variation))
;      ; Error case
;      (log/error :fn ::vrs-allele-for-variation :msg "Error in VRS normalization request" :status status :response response)
;      )
;    ))


(defn variation-triples [msg]
  (let [msg (assoc-in msg [:content :release_date] (:release_date msg))
        msg (assoc-in msg [:content :event_type] (:event_type msg))
        msg (:content msg)

        vrd-unversioned (str (ns-cg "VariationDescriptor_") (:id msg))
        vrd-versioned (str vrd-unversioned "." (:release_date msg))

        clinvar-variation-iri (q/resource (str iri/clinvar-variation (:id msg)))]
    ;variation-rule-descriptor-iri (q/resource (str vcv-statement-unversioned-iri "_variation_rule_descriptor." (:release_date msg)))]
    (concat
      [; VRS Variation Rule Descriptor
       ; statement: <proposition> <has confidence + direction> <strength>
       ;[vrd-versioned :rdf/type (ccommon/variation-geno-type (:subclass_type msg))]
       ;[vrd-versioned :rdf/type (ccommon/variation-vrs-type (:subclass_type msg))]
       [vrd-versioned :rdf/type :vrs/VariationDescriptor]
       [vrd-versioned :rdf/type (q/resource (ns-cg "ClinVarVariation"))]
       [vrd-versioned :rdf/type (q/resource (ns-cg "ClinVarObject"))] ; For tracking clinvar objects

       ; Rule Descriptor describes object: variation
       [vrd-versioned :sepio/has-object clinvar-variation-iri]
       [vrd-versioned :dc/is-version-of vrd-unversioned]
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
      (ccommon/fields-to-extensions vrd-versioned (dissoc msg :id :release_date)))))

(defn resource-to-out-triples
  "Uses steppable interface of RDFResource to obtain all the out properties and load
  them into a Model. These triples can be used as input to l/statements-to-model.
  NOTE: that only works when all the properties of the resource are in property-names.edn"
  [resource]
  ; [k v] -> [r k v]
  (map #(cons resource %) (into {} resource)))

(defmethod ccommon/clinvar-to-model :variation [event]
  (log/debug :fn ::clinvar-to-model :event event)
  (let [model (-> event
                  :genegraph.transform.clinvar.core/parsed-value
                  variation-triples
                  (#(do (log/info :triples %) %))
                  l/statements-to-model
                  (#(do (log/info :model %) %))
                  (#(ccommon/mark-prior-replaced % (q/resource clinvar-variation-type))))]
    (log/debug :fn ::clinvar-to-model :model model)
    model))

(defmethod ccommon/clinvar-model-to-jsonld :variation [event]
  (let [model (::q/model event)]
    (ccommon/model-framed-to-jsonld model variation-frame)))
