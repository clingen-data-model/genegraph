(ns genegraph.transform.clinvar.variation
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.database.names :refer [local-property-names
                                              local-class-names
                                              property-uri->keyword
                                              prefix-ns-map]]
            [genegraph.util :refer [str->bytestream]]
            [genegraph.database.util :refer [tx]]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.source.graphql.experimental-schema :as experimental-schema]
            [clojure.pprint :refer [pprint]]
            [clojure.datafy :refer [datafy]]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [io.pedestal.log :as log])
  (:import (org.apache.jena.rdf.model Model Statement)
           (java.io ByteArrayInputStream)
           (clojure.lang Keyword)))

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


; TODO when no 'canonical' expressions found, fall back to Text VRS type
(defn prioritized-variation-expression
  "Attempts to return a 'canonical' expression of the variation in ClinVar.
  Takes a clinvar-raw variation message. Returns one string expression.
  Tries GRCh38, GRCh37, may add additional options in the future.

  NOTE: .content.content should be parsed already, but it tries to parse it again if not."
  [variation-msg]
  (log/trace :fn ::prioritized-variation-expression :variation-msg variation-msg)
  (let [nested-content (-> variation-msg (util/parse-nested-content) :content :content)]
    (letfn [(get-hgvs [nested-content assembly-name]
              (let [hgvs-list (-> nested-content (get "HGVSlist") (get "HGVS") util/into-sequential-if-not)
                    filtered (filter #(= assembly-name (get-in % ["NucleotideExpression" "@Assembly"])) hgvs-list)]
                (if (< 1 (count filtered)) (log/warn :fn ::prioritized-variation-expression
                                                     :msg (str "Multiple expressions for variation for assembly: " assembly-name)
                                                     :variation variation-msg))
                (-> filtered first (get "NucleotideExpression") (get "Expression") (get "$"))))
            (get-spdi [nested-content]
              (-> nested-content (get "CanonicalSPDI") (get "$")))]
      (let [exprs (for [val-opt [{:fn #(get-spdi nested-content)
                                  :type :spdi}
                                 {:fn #(get-hgvs nested-content "GRCh38")
                                  :type :hgvs}
                                 {:fn #(get-hgvs nested-content "GRCh37")
                                  :type :hgvs}
                                 ; Fallback to using the variation id
                                 {:fn #(-> variation-msg :content :id)
                                  :type :text}]]
                    (let [expr ((get val-opt :fn))]
                      (when expr {:expr expr :type (:type val-opt)})))]
        ; Return first non-nil
        (some identity exprs)))))

(defn hgvs-syntax-from-change
  "Takes a change string like g.119705C>T and returns a VRS syntax string like \"hgvs.g\""
  [^String change]
  (if change
    (cond (.startsWith change "g.") "hgvs.g"
          (.startsWith change "c.") "hgvs.c"
          (.startsWith change "p.") "hgvs.p"
          :default (let [e (ex-info "Unknown hgvs change syntax" {:change change})]
                     (log/error :message (ex-message e) :data (ex-data e))
                     (log/error :message "Defaulting to 'hgvs' for change" :change change)
                     "hgvs"))
    (do (log/warn :message "No change provided, falling back to text syntax")
        "text")))

(defn nucleotide-hgvs
  "Takes an object in the form of ClinVar's HGVSlist.
  Returns an {:expression :assembly :syntax} map if a nucleotide HGVS expression is present."
  [hgvs-obj]
  (when (get hgvs-obj "NucleotideExpression")
    {:expression (-> hgvs-obj (get "NucleotideExpression") (get "Expression") (get "$"))
     :assembly (-> hgvs-obj (get "NucleotideExpression") (get "Assembly"))
     :syntax (hgvs-syntax-from-change (-> hgvs-obj (get "NucleotideExpression") (get "@change")))}))

(defn protein-hgvs
  "Takes an object in the form of ClinVar's HGVSlist.
  Returns an {:expression :assembly :syntax} map if a protein HGVS expression is present."
  [hgvs-obj]
  (when (get hgvs-obj "ProteinExpression")
    {:expression (-> hgvs-obj (get "ProteinExpression") (get "Expression") (get "$"))
     :syntax (hgvs-syntax-from-change (-> hgvs-obj (get "ProteinExpression") (get "@change")))}))

(defn get-all-expressions
  "Attempts to return a 'canonical' expression of the variation in ClinVar.
  Takes a clinvar-raw variation message. Returns one string expression.
  Tries GRCh38, GRCh37, may add additional options in the future.

  Returns seq of maps of
  :expression
  :syntax
  :syntax-version (not currently implemented for any)

  Example of HGVSlist objects being parsed:
  ProteinExpression:
  {\"@Type\" \"protein\",
   \"ProteinExpression\"
      {\"@change\" \"p.Thr380Met\",
       \"@sequenceAccession\" \"P00439\",
       \"@sequenceAccessionVersion\" \"P00439\",
       \"Expression\"
          {\"$\" \"P00439:p.Thr380Met\"}}}

  NucleotideExpression is similar format.

  NOTE: .content.content should be parsed already, but it tries to parse it again if not."
  [variation-msg]
  (log/debug :fn ::get-all-expressions :variation-msg variation-msg)
  (let [nested-content (-> variation-msg (util/parse-nested-content) :content :content)
        hgvs-list (-> nested-content (get "HGVSlist") (get "HGVS") util/into-sequential-if-not)]
    (let [expressions
          (filter
           #(not (nil? %))
           (concat
            (->> hgvs-list
                 (map (fn [hgvs-obj]
                        (log/trace :fn ::get-all-expressions :hgvs-obj hgvs-obj)
                        (let [outputs (filter not-empty [(nucleotide-hgvs hgvs-obj)
                                                         (protein-hgvs hgvs-obj)])]
                          (if (empty? outputs)
                            (let [e (ex-info "Found no HGVS expressions" {:hgvs-obj hgvs-obj :variation-msg variation-msg})]
                              (log/error :message (ex-message e) :data (ex-data e))
                                ;(throw e)
                              ))
                          outputs)))
                 (apply concat))
            [(let [spdi-expr (-> nested-content (get "CanonicalSPDI") (get "$"))]
               (when spdi-expr
                 {:expression spdi-expr
                  :syntax "spdi"}))]))]
      expressions)))

(defn make-member
  "Creates a set of VariationMember triples based on an expression and syntax.
  Returns a seq of seqs"
  [node-iri expression syntax syntax-version]
  (log/trace :fn :make-member :node-iri node-iri
             :expression expression
             :syntax syntax
             :syntax-version syntax-version)
  (let [member-iri (l/blank-node)
        expression-iri (l/blank-node)]
    (concat
     [[node-iri :vrs/members member-iri]
      [member-iri :rdf/type :vrs/VariationMember]
      [member-iri :vrs/expressions expression-iri]
      [expression-iri :rdf/type :vrs/Expression]
      [expression-iri :vrs/syntax syntax]
      [expression-iri :rdf/value expression]]
     (if (seq syntax-version)
       [[expression-iri :vrs/syntax-version syntax-version]]))))

(defn variation-triples
  "Returns a collection of triples"
  [msg]
  (let [msg (util/parse-nested-content msg)
        content (:content msg)

        vrd-unversioned (str (ns-cg "VariationDescriptor_") (:id content))
        vd-iri (str vrd-unversioned "." (:release_date msg))
        clinvar-variation-iri (q/resource (str iri/clinvar-variation (:id content)))
        canonical-variation-obj (prioritized-variation-expression msg)
        canonical-variation-expression (q/resource (:expr canonical-variation-obj))
        canonical-variation-expression-type (q/resource (name (:type canonical-variation-obj)))]
    (concat
     [; VRS Variation Descriptor
      [vd-iri :rdf/type :vrs/CategoricalVariationDescriptor]
      [vd-iri :rdf/type (q/resource (ns-cg "ClinVarVariation"))]
       ; For tracking clinvar objects and identifying the named graph in add-iri
      [vd-iri :rdf/type (q/resource (ns-cg "ClinVarObject"))]
      [vd-iri :rdfs/label (:name content)]

       ; Variation Descriptor describes object: variation
       ; TODO more gracefully handle nil/exception from normalization service
       ;[vd-iri :sepio/has-object (vicc/vrs-allele-for-variation (prioritized-variation-expression msg))]
      [vd-iri :rdf/value canonical-variation-expression]
       ; This identifies the type of expression to downstream processes that need/benefit from that information
      [canonical-variation-expression :rdf/type canonical-variation-expression-type]

      [vd-iri :dc/is-version-of (q/resource vrd-unversioned)]
       ; TODO set this to version
       ; Add clinvar's version field to extensions
      [vd-iri :owl/version-info (:release_date msg)]
      [vd-iri :cg/release-date (:release_date msg)]

       ; xrefs
      [vd-iri :vrs/xrefs (str (get prefix-ns-map "clinvar")
                              (:id content))]
      [vd-iri :vrs/xrefs (str clinvar-variation-iri)]]
      ; TODO reverse link to VCV? Or rely on VCV->variation that should be added by VCV

      ; include all genomic, protein, spdi expressions as variation members
     (let [members (let [exprs (get-all-expressions msg)]
                     (doall
                      (for [expr exprs]
                        (do (log/trace :fn ::variation-triples :msg "Making members" :expr expr)
                            (make-member vd-iri
                                         (:expression expr)
                                         (:syntax expr)
                                         (:syntax-version expr))))))]
       (apply concat members))

      ; Extensions
     (common/fields-to-extensions vd-iri (merge (dissoc content :id :release_date :name)
                                                 ; Put this back into a string
                                                {:content (json/generate-string (:content content))}
                                                {:clinvar_variation clinvar-variation-iri})))))

(defn resource-to-out-triples
  "Uses steppable interface of RDFResource to obtain all the out properties and load
  them into a Model. These triples can be used as input to l/statements-to-model.
  NOTE: that only works when all the properties of the resource are in property-names.edn"
  [resource]
  ; [k v] -> [r k v]
  (map #(cons resource %) (into {} resource)))

(defn add-triple!
  "Adds a triple to a model. Takes a triple ([s p o])."
  ([^Model model triple]
   (log/debug :fn ::add-triple :triple triple)
   (let [stmt (l/construct-statement triple)]
     (.add model stmt)
     model)))

(defn remove-triple!
  "Deletes a triple from a model. Takes a triple ([s p o])."
  ([^Model model triple]
   (log/debug :fn ::remove-triple :triple triple)
   (let [stmt-to-remove (l/construct-statement triple)]
     (if (not (.contains model stmt-to-remove))
       (let [e (ex-info "Statement not found in model" {:fn ::remove-triple :model model :stmt-to-remove stmt-to-remove})]
         (log/error :message (ex-message e) :data (ex-data e))
         (throw e))
       (.remove model stmt-to-remove))
     model)))

(defn add-vrs-model
  "Takes a model containing 1 :vrs/CategoricalVariationDescriptor, if tries to convert the :rdf/value
   triple to a node of the VRS variation representation of the expression."
  [model]
  (let [expr-kw :rdf/value
        descriptor-resource (first (q/select "SELECT ?iri WHERE { ?iri a :vrs/CategoricalVariationDescriptor }" {} model))
        expressions (q/select "SELECT ?object WHERE { ?iri :rdf/value ?object }"
                              {:iri descriptor-resource}
                              model)]
    (when (< 1 (count expressions)) (let [e (ex-info "More than 1 object in model" {:model model
                                                                                    :object-exprs expressions})]
                                      (log/error :message (ex-message e) :data (ex-data e)) (throw e)))
    (let [expression (first expressions)
          expression-type (q/ld1-> expression [:rdf/type])
          vrs-obj (vicc/vrs-variation-for-expression expression (keyword (str expression-type)))]
      (if (empty? vrs-obj)
        (do (let [e (ex-info "No variation received from VRS normalization" {:fn ::add-vrs-model :expression expression})]
              (log/error :message (ex-message e) :data (ex-data e))
              ;; (throw e)
              ))
        (let [vrs-id (get vrs-obj "_id")
              vrs-model (l/read-rdf (str->bytestream (json/generate-string vrs-obj)) {:format :json-ld})]
          (log/debug :fn ::add-vrs-model
                     :vrs-id vrs-id
                     :vrs-obj vrs-obj)
          (when (empty? vrs-id)
            (let [e (ex-info "Could not determine variation descriptor iri" {:vrs-obj vrs-obj :model vrs-model})]
              (log/error :message (ex-message e) :data (ex-data e)) (throw e)))
          (let [link-triple [descriptor-resource :rdf/value (q/resource vrs-id)]]
            ; Remove the previous object, which is just the expression and syntax
            (let [triples-to-remove [[descriptor-resource :rdf/value expression]
                                     [expression :rdf/type expression-type]]]
              (log/debug :msg "Deleting previous expression triples" :triples-to-remove triples-to-remove)
              (doseq [triple triples-to-remove]
                (remove-triple! model triple)))
            ; Join the descriptor and the VRS variation
            (-> (q/union model vrs-model)
                (add-triple! link-triple))))))))

(defmethod common/clinvar-to-model :variation [event]
  (log/debug :fn ::clinvar-to-model :event event)
  (let [model (-> event
                  :genegraph.transform.clinvar.core/parsed-value
                  variation-triples
                  (#(do (log/debug :triples %) %))
                  l/statements-to-model
                  add-vrs-model
                  ;(#(do (log/debug :model %) %))
                  (#(common/mark-prior-replaced % (q/resource clinvar-variation-type))))]
    ;(log/debug :fn ::clinvar-to-model :model model)
    model))

(def variation-context
  {"@context"
   {; Properties
    ;"object" {"@id" (str (get local-property-names :sepio/has-object))
    ;          "@type" "@id"}
    "is_version_of" {"@id" (str (get local-property-names :dc/is-version-of))
                     "@type" "@id"}
    "type" {"@id" "@type"
            "@type" "@id"}
    ;"release_date" {"@id" (str (get local-property-names :cg/release-date))}
    "name" {"@id" (str (get local-property-names :vrs/name))}

    ;"value" {"@id" "@value"}
    "value" {"@id" "rdf:value"}

    "replaces" {"@id" (str (get local-property-names :dc/replaces))
                "@type" "@id"}
    "is_replaced_by" {"@id" (str (get local-property-names :dc/is-replaced-by))
                      "@type" "@id"}
    "version" {"@id" (str (get local-property-names :owl/version-info))}
    "_id" {"@id" "@id"
           "@type" "@id"}
    "Extension" {"@id" (str (get local-class-names :vrs/Extension))}
    "CategoricalVariationDescriptor" {"@id" (str (get local-class-names :vrs/CategoricalVariationDescriptor))}

    ; eliminate vrs prefixes on vrs variation terms
    ; VRS properties
    "variation" {"@id" (str (get prefix-ns-map "vrs") "variation")}
    "complement" {"@id" (str (get prefix-ns-map "vrs") "complement")}
    "interval" {"@id" (str (get prefix-ns-map "vrs") "interval")}
    "start" {"@id" (str (get prefix-ns-map "vrs") "start")}
    "end" {"@id" (str (get prefix-ns-map "vrs") "end")}
    "location" {"@id" (str (get prefix-ns-map "vrs") "location")}
    "state" {"@id" (str (get prefix-ns-map "vrs") "state")}
    "sequence_id" {"@id" (str (get prefix-ns-map "vrs") "sequence_id")}
    "sequence" {"@id" (str (get prefix-ns-map "vrs") "sequence")}

    ; map plurals to known guaranteed array types
    "members" {"@id" (str (get local-property-names :vrs/members))
               "@container" "@set"}
    "extensions" {"@id" (str (get local-property-names :vrs/extensions))
                  "@container" "@set"}
    "expressions" {"@id" (str (get local-property-names :vrs/expressions))
                   "@container" "@set"}

    ; VRS entities
    "CanonicalVariation" {"@id" (str (get prefix-ns-map "vrs") "CanonicalVariation")
                          "@type" "@id"}
    "Allele" {"@id" (str (get prefix-ns-map "vrs") "Allele")
              "@type" "@id"}
    "SequenceLocation" {"@id" (str (get prefix-ns-map "vrs") "SequenceLocation")
                        "@type" "@id"}
    "SequenceInterval" {"@id" (str (get prefix-ns-map "vrs") "SequenceInterval")
                        "@type" "@id"}
    "Number" {"@id" (str (get prefix-ns-map "vrs") "Number")
              "@type" "@id"}
    "LiteralSequenceExpression" {"@id" (str (get prefix-ns-map "vrs") "LiteralSequenceExpression")
                                 "@type" "@id"}
    "VariationMember" {"@id" (str (get prefix-ns-map "vrs") "VariationMember")
                       "@type" "@id"}
    ; Prefixes
    ;"https://vrs.ga4gh.org/terms/"
    "rdf" {"@id" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           "@prefix" true}
    "rdfs" {"@id" "http://www.w3.org/2000/01/rdf-schema#"
            "@prefix" true}
    "vrs" {"@id" (get prefix-ns-map "vrs")
           "@prefix" true}
    "cgterms" {"@id" (get prefix-ns-map "cgterms")
               "@prefix" true}}})


;(def graphql-schema (genegraph.source.graphql.experimental-schema/schema))
;(defn graphql-query
;  "Function not used except for evaluating queries in the REPL
;  may consider moving into test namespace in future"
;  ([query-str]
;   (graphql-query query-str nil))
;  ([query-str variables]
;   (tx (lacinia/execute graphql-schema query-str variables nil))))


(def variation-descriptor-query
  "
query($variation_iri:String) {
  variation_descriptor_query(variation_iri: $variation_iri) {
    id: iri
    type: __typename
    ... on CategoricalVariationDescriptor {
      label
      object {
        id: iri
        type: __typename
        ... on CanonicalVariation {
          complement
          variation {
            ... alleleFields
          }
        }
        ... on Allele {
          ...alleleFields
        }
      }
      xrefs
      members {
        id: iri
        type: __typename
        expressions {
          type: __typename
          value
          syntax
          syntax_version
        }
      }
      extensions {
        type: __typename
        name
        value
      }
    }
  }
}

fragment alleleFields on Allele {
  location {
    id: iri
    type: __typename
    interval {
      type: __typename
      start {
        type: __typename
        value
      }
      end {
        type: __typename
        value
      }
    }
  }
}
")

(defmethod common/clinvar-add-event-graphql :variation [event]
  (let [iri (:genegraph.annotate/iri event)]
    (assoc event :graphql-params {:query variation-descriptor-query
                                  :variables {:variation_iri (str iri)}})))


(defmethod common/clinvar-model-to-jsonld :variation [event]
  (let [model (::q/model event)]
    (-> model
        (jsonld/model-to-jsonld)
        (jsonld/jsonld-to-jsonld-framed (json/generate-string variation-frame))
        ; TODO may consider adding scoped context to the vrs variation object, with vocab=vrs
        (jsonld/jsonld-compact (json/generate-string (merge variation-context))))))
