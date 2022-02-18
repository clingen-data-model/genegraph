(ns genegraph.transform.clinvar.variation
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.database.names :refer [local-property-names
                                              property-uri->keyword
                                              prefix-ns-map]]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [clojure.pprint :refer [pprint]]
            [clojure.datafy :refer [datafy]]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [io.pedestal.log :as log]
            [genegraph.transform.clinvar.util :as util])
  (:import (org.apache.jena.rdf.model Model)
           (java.io ByteArrayInputStream)))

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
  (log/info :variation-msg variation-msg)
  (let [hgvs-list (-> variation-msg (util/parse-nested-content) :content :content (get "HGVSlist") (get "HGVS"))]
    (log/info :hgvs-list hgvs-list)
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
  (let [msg (util/parse-nested-content msg)
        ;msg (assoc-in msg [:content :release_date] (:release_date msg))
        ;msg (assoc-in msg [:content :event_type] (:event_type msg))
        content (:content msg)

        vrd-unversioned (str (ns-cg "VariationDescriptor_") (:id content))
        vd-iri (str vrd-unversioned "." (:release_date msg))
        ;variation-rule-descriptor-iri (q/resource (str vcv-statement-unversioned-iri "_variation_rule_descriptor." (:release_date msg)))]
        clinvar-variation-iri (q/resource (str iri/clinvar-variation (:id content)))]
    (concat
      [; VRS Variation Descriptor
       ;[vrd-versioned :rdf/type (ccommon/variation-geno-type (:subclass_type content))]
       ;[vrd-versioned :rdf/type (ccommon/variation-vrs-type (:subclass_type content))]
       [vd-iri :rdf/type :vrs/VariationDescriptor]
       [vd-iri :rdf/type (q/resource (ns-cg "ClinVarVariation"))]
       ; For tracking clinvar objects and identifying the named graph
       [vd-iri :rdf/type (q/resource (ns-cg "ClinVarObject"))]

       ; Variation Descriptor describes object: variation
       ;[vd-iri :sepio/has-object clinvar-variation-iri]
       ; TODO handle nil return value from normalization service
       ;[vd-iri :sepio/has-object (vicc/vrs-allele-for-variation (prioritized-variation-expression msg))]
       [vd-iri :sepio/has-object (q/resource (prioritized-variation-expression msg))]
       [vd-iri :dc/is-version-of (q/resource vrd-unversioned)]
       [vd-iri :cg/release-date (:release_date msg)]


       ;(q/resource (str iri/clinvar-variation (:variation_id msg)))
       ; TODO reverse link to VCV? Or rely on VCV->variation that should be added by VCV
       ;;[subject-iri :rdf/type (ns-vrs "Allele")]

       ;; Variation Rule Descriptor
       ;[variation-rule-descriptor-iri :rdf/type (q/resource (ns-cg "VariationRuleDescriptor"))]
       ;[variation-rule-descriptor-iri :vrs/xref clinvar-variation-iri]]
       ]
      ; TODO Label (variant name?) should be added to this same VariationRuleDescriptor when received from variation record

      ; Extensions
      (common/fields-to-extensions vd-iri (merge (dissoc content :id :release_date)
                                                 ; Put this back into a string
                                                 (assoc content :content (json/generate-string (:content content)))
                                                 {:clinvar_variation clinvar-variation-iri})))))

(defn resource-to-out-triples
  "Uses steppable interface of RDFResource to obtain all the out properties and load
  them into a Model. These triples can be used as input to l/statements-to-model.
  NOTE: that only works when all the properties of the resource are in property-names.edn"
  [resource]
  ; [k v] -> [r k v]
  (map #(cons resource %) (into {} resource)))

(defn string->InputStream [s]
  (ByteArrayInputStream. (.getBytes s)))

(defn add-vrs-model [model]
  (let [object-exprs (q/select "SELECT ?object WHERE { ?iri :sepio/has-object ?object }" {} model)]
    (when (< 1 (count object-exprs)) (let [e (ex-info "More than 1 object in model" {:model model
                                                                                     :object-exprs object-exprs})]
                                       (log/error :message (ex-message e) :data (ex-data e)) (throw e)))
    (let [object-expr (first object-exprs)
          vrs-json (vicc/vrs-allele-for-variation object-expr)
          vrs-model (l/read-rdf (string->InputStream (json/generate-string vrs-json)) {:format :json-ld})]
      (log/info :fn ::add-vrs-model :vrs-model vrs-model)
      (let [vrs-jsonld (jsonld/model-to-jsonld vrs-model)
            vrs-jsonld-framed (jsonld/jsonld-to-jsonld-framed vrs-jsonld
                                                              (json/generate-string
                                                                {"https://vrs.ga4gh.org/type" "VariationDescriptor"}))]
        (log/debug :vrs-jsonld vrs-jsonld)
        (log/debug :vrs-jsonld-framed vrs-jsonld-framed))
      ; Add a triple linking the model iri to the vrs variation model via :sepio/has-object
      (let [variation-descriptor-i (first (q/select "SELECT ?iri WHERE { ?iri <https://vrs.ga4gh.org/type> \"VariationDescriptor\" }"
                                                    {} vrs-model))
            i (first (q/select "SELECT ?iri WHERE { ?iri :sepio/has-object ?object }" {} model))
            stmts [[i :sepio/has-object variation-descriptor-i]]
            link-model (l/statements-to-model stmts)]
        (when (empty? variation-descriptor-i)
          (let [e (ex-info "Could not determine variation descriptor iri" {:model vrs-model})]
            (log/error :message (ex-message e) :data (ex-data e)) (throw e)))
        (log/debug :msg "Joining model, vrs model, and linking model" :link-model link-model)
        (q/union model vrs-model link-model)))))

(defmethod common/clinvar-to-model :variation [event]
  (log/debug :fn ::clinvar-to-model :event event)
  (let [model (-> event
                  :genegraph.transform.clinvar.core/parsed-value
                  variation-triples
                  (#(do (log/info :triples %) %))
                  l/statements-to-model
                  add-vrs-model
                  (#(do (log/info :model %) %))
                  (#(common/mark-prior-replaced % (q/resource clinvar-variation-type))))]
    (log/debug :fn ::clinvar-to-model :model model)
    model))

(def variation-context
  {"@context" {; Properties
               "object" {"@id" (str (get local-property-names :sepio/has-object))
                         "@type" "@id"}
               "is_version_of" {"@id" (str (get local-property-names :dc/is-version-of))
                                "@type" "@id"}
               "type" {"@id" "@type"
                       "@type" "@id"}
               "release_date" {"@id" (str (get local-property-names :cg/release-date))}
               "extension" {"@id" (str (get local-property-names :vrs/extension))}
               "name" {"@id" (str (get local-property-names :vrs/name))}
               "value" {"@id" (str (get local-property-names :vrs/value))}

               ; Prefixes
               "vrs" {"@id" (get prefix-ns-map "vrs");"https://vrs.ga4gh.org/terms/"
                      "@prefix" true}
               }})


(defmethod common/clinvar-model-to-jsonld :variation [event]
  (let [model (::q/model event)]
    (-> model
        (jsonld/model-to-jsonld)
        (jsonld/jsonld-to-jsonld-framed (json/generate-string variation-frame))
        (jsonld/jsonld-compact (json/generate-string variation-context)))))
