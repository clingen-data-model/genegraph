(ns genegraph.transform.clinvar.variation
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.database.names :refer [local-property-names
                                              local-class-names
                                              property-uri->keyword
                                              prefix-ns-map]]
            [genegraph.util :refer [str->bytestream
                                    dissoc-ns]]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.annotate.cnv :as cnv]
            [clojure.pprint :refer [pprint]]
            [clojure.datafy :refer [datafy]]
            ;; [clojure.string :as s]
            [cheshire.core :as json]
            [io.pedestal.log :as log])
  (:import (org.apache.jena.rdf.model Model Statement)
           (java.io ByteArrayInputStream)))

(def clinvar-variation-type (ns-cg "ClinVarVariation"))
(def variation-frame
  "Frame map for variation"
  {#_#_"@type" clinvar-variation-type
   #_#_"@type" "https://vrs.ga4gh.org/terms/CategoricalVariationDescriptor"
   "@type" "https://vrs.ga4gh.org/terms/CanonicalVariationDescriptor"})

(declare variation-context)

(defn add-contextualized [event]
  (let [data (:genegraph.annotate/data event)]
    (assoc event
           :genegraph.annotate/data-contextualized
           (merge data variation-context))))


(defn prioritized-variation-expression
  "Attempts to return a 'canonical' expression of the variation in ClinVar.
  Takes a clinvar-raw variation message. Returns one string expression.
  Tries GRCh38, GRCh37, may add additional options in the future.
  When no 'canonical' expressions found, falls back to Text VRS using variation.id.

  NOTE: .content.content must be parsed already."
  [variation-msg]
  (log/trace :fn ::prioritized-variation-expression :variation-msg variation-msg)
  (let [nested-content (-> variation-msg :content :content)]
    (letfn [(get-hgvs [nested-content assembly-name]
              (let [hgvs-list (-> nested-content (get "HGVSlist") (get "HGVS") util/into-sequential-if-not)
                    filtered (filter #(= assembly-name (get-in % ["NucleotideExpression" "@Assembly"])) hgvs-list)]
                (when (< 1 (count filtered))
                  (log/warn :fn ::prioritized-variation-expression
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
                                 ;; Fallback to using the variation id
                                 {:fn #(-> variation-msg :content :id)
                                  :type :text}]]
                    (let [expr ((get val-opt :fn))]
                      (when expr {:expr expr :type (:type val-opt)})))]
        ;; Return first non-nil
        (some identity exprs)))))

(defn hgvs-syntax-from-change
  "Takes a change string like g.119705C>T and returns a VRS syntax string like \"hgvs.g\""
  [^String change]
  (if change
    (cond (.startsWith change "g.") "hgvs.g"
          (.startsWith change "c.") "hgvs.c"
          (.startsWith change "p.") "hgvs.p"
          :else (do (log/warn :message "Unknown hgvs change syntax. Defaulting to 'hgvs'."
                              :change change)
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
  (let [nested-content (-> variation-msg :content :content)
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
                          (when (empty? outputs)
                            (let [e (ex-info "Found no HGVS expressions" {:hgvs-obj hgvs-obj :variation-msg variation-msg})]
                              (log/error :message (ex-message e) :data (ex-data e))))
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
     (when (seq syntax-version)
       [[expression-iri :vrs/syntax-version syntax-version]]))))

#_(defn preprocess-copy-number
    [event]
    (let [message (:genegraph.transform.clinvar.core/parsed-value event)
          variation (:content message)
          copy-number? (.startsWith (:variation_type variation)
                                    "copy number")]
      (-> event
          (assoc ::copy-number? copy-number?)
          (#(if copy-number?
              (assoc % ::cnv (cnv/parse (-> variation :name)))
              %)))))

#_(defn preprocess-expressions
    [event]
    (let [message (:genegraph.transform.clinvar.core/parsed-value event)
          variation (:content message)]
      (-> event
          (#(assoc % ::prioritized-expression (cond (::copy-number? %) {:type :cnv
                                                                        :expr (::cnv %)}
                                                    :else (prioritized-variation-expression message))))
          (assoc ::other-expressions (get-all-expressions message)))))

(defn variation-preprocess
  "Performs some annotations to the variation event in order to enable downstream transformation.

   Parses expressions and determines their types (spdi, hgvs, clinvar copy number).
   Adds locally namespaced keywords to the message."
  [event]
  (let [value (:genegraph.transform.clinvar.core/parsed-value event)]
    (-> event
        (assoc ::copy-number? (.startsWith (-> value
                                               :content
                                               :variation_type)
                                           "copy number"))
        (#(if (::copy-number? %) (assoc % ::cnv (cnv/parse (-> value :content :name))) %))
        (#(assoc % ::prioritized-expression (cond (::copy-number? %) {:type :cnv
                                                                      :expr (::cnv %)}
                                                  :else (prioritized-variation-expression value))))
        (assoc ::other-expressions (get-all-expressions value)))))

(defn make-index-replacer
  "Returns a function which when called with a value,
   replaces the INDEX of INPUT-VECTOR with that value."
  [input-vector index]
  (fn [value] (assoc input-vector index value)))

(defn recursive-replace-keys
  "Recursively replace keys in input-map and its values, for keys matching
   key-match-fn, by applying key-mutate-fn"
  [input-map key-match-fn key-mutate-fn]
  (letfn [(apply-to-key [k]
            (if (key-match-fn k) (key-mutate-fn k) k))
          (apply-to-value [v]
            (cond (map? v) (recursive-replace-keys v key-match-fn key-mutate-fn)
                  (sequential? v) (map #(apply-to-value %) v)
                  :else v))]
    (into {} (map (fn [[k v]]
                    (vector (apply-to-key k)
                            (apply-to-value v)))
                  input-map))))

(defn make-member-map
  "Creates a set of VariationMember triples based on an expression and syntax.
  Returns a seq of seqs"
  [node-iri expression syntax syntax-version]
  (log/trace :fn :make-member :node-iri node-iri
             :expression expression
             :syntax syntax
             :syntax-version syntax-version)
  (let []
    ;;member-iri (l/blank-node)
    ;;expression-iri (l/blank-node)
    {;;:id member-iri
     :type "VariationMember"
     :expressions [(merge
                    {;;expression-iri
                     :type "Expression"
                     :syntax syntax
                     :value expression}
                    (when syntax-version
                      {:syntax_version syntax-version}))]}))

(defn get-vrs-variation-map
  "Takes a map with :expression (String or Map) and :expression-type (Keyword).

   Returns a map containing :model and :iri"
  [{expression :expression
    expression-type :expression-type}]
  (let [vrs-obj (vicc/vrs-variation-for-expression
                 (-> expression)
                 (-> expression-type keyword))]
    (if (empty? vrs-obj)
      (let [e (ex-info "No variation received from VRS normalization" {:fn :add-vrs-model :expression expression})]
        (log/error :message (ex-message e) :data (ex-data e))
        (throw e))
      (let [vrs-obj-pretty (-> vrs-obj
                               (recursive-replace-keys
                                (fn [k] (= "_id" k))
                                (fn [k] "id"))
                                ;; Keywordize keys by round-tripping json
                               (json/generate-string)
                               (json/parse-string true))
            vrs-id (:id vrs-obj-pretty)]
        (log/debug :fn :add-vrs-model :vrs-id vrs-id :vrs-obj vrs-obj-pretty)
        {:iri vrs-id :variation vrs-obj-pretty}))))


(defn add-data-for-variation
  "Returns msg with :genegraph.annotate/data and :genegraph.annotate/data-contextualized added.

   Note this function may execute HTTP calls if the normalized version of canonical
   variation expressions are not already cached locally."
  [event]
  (let [event (variation-preprocess event)
        message (-> event
                    :genegraph.transform.clinvar.core/parsed-value)
        variation (:content message)
        vrd-unversioned (str (ns-cg "VariationDescriptor_") (:id variation))
        vd-iri (str vrd-unversioned "." (:release_date message))
        clinvar-variation-iri (str iri/clinvar-variation (:id variation))]
    (-> event
        (assoc
         :genegraph.annotate/data
         {:id vd-iri
          :type "CanonicalVariationDescriptor"
          :label (:name variation)
          :extensions (common/fields-to-extension-maps
                       (merge (dissoc variation :id :release_date :name :content)
                              {:clinvar_variation clinvar-variation-iri}))
          :description (:name variation)
          :xrefs [(str (get prefix-ns-map "clinvar") (:id variation))
                  (str clinvar-variation-iri)]
          :alternate_labels []
          :members (->> (for [expr (::other-expressions event)]
                          (make-member-map vd-iri
                                           (:expression expr)
                                           (:syntax expr)
                                           (:syntax-version expr)))
                        (into []))
          :subject_variation_descriptor ()
            ;;  :value_id ()
          :value (let [vrs-ret (get-vrs-variation-map
                                {:expression (-> event ::prioritized-expression :expr)
                                 :expression-type (-> event ::prioritized-expression :type)})]
                   (:variation vrs-ret))
          :record_metadata {:type "RecordMetadata"
                            :is_version_of vrd-unversioned
                            :version (:release_date message)}})
        (assoc :genegraph.annotate/iri vd-iri)
        add-contextualized)))

(defn record-metadata-resource-for-output
  [record-metadata-resource]
  (when record-metadata-resource
    {:type (q/ld1-> record-metadata-resource [:rdf/type])
     :is_version_of (q/ld1-> record-metadata-resource [:dc/is-version-of])
     :version (q/ld1-> record-metadata-resource [:owl/version-info])}))

(defn variation-descriptor-resource-for-output
  "Takes a VariationDescriptor resource and returns a GA4GH edn structure"
  [descriptor-resource]
  {:id (str descriptor-resource)
   :type (q/ld1-> descriptor-resource [:rdf/type])
   :label (q/ld1-> descriptor-resource [:rdfs/label])
   :extensions ()
   :description ()
   :xrefs ()
   :alternate_labels ()
   :members ()
   :subject_variation_descriptor ()
   :value ()
   :record_metadata ()})

(def myval "hello")

(def variation-context
  {"@context"
   {; Properties
    "is_version_of" {"@id" (str (get local-property-names :dc/is-version-of))
                     "@type" "@id"}
    "type" {"@id" "@type"
            "@type" "@id"}
    "name" {"@id" (str (get local-property-names :vrs/name))}

    ;"value" {"@id" "@value"}
    "value" {"@id" "rdf:value"}
    "label" {"@id" "rdfs:label"}

    "replaces" {"@id" (str (get local-property-names :dc/replaces))
                "@type" "@id"}
    "is_replaced_by" {"@id" (str (get local-property-names :dc/is-replaced-by))
                      "@type" "@id"}
    "version" {"@id" (str (get local-property-names :owl/version-info))}
    "id" {"@id" "@id"
          "@type" "@id"}
    ;; "_id" {"@id" "@id"
    ;;        "@type" "@id"}
    "Extension" {"@id" (str (get local-class-names :vrs/Extension))}
    "CategoricalVariationDescriptor"
    {"@id" (str (get local-class-names :vrs/CategoricalVariationDescriptor))
     "@type" "@id"}
    "CanonicalVariationDescriptor"
    {"@id" (str (get local-class-names :vrs/CanonicalVariationDescriptor))
     "@type" "@id"}

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
    "record_metadata" {"@id" (str (get prefix-ns-map "vrs") "record_metadata")}

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
    "RecordMetadata" {"@id" (str (get prefix-ns-map "vrs") "RecordMetadata")
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

(def variation-descriptor-query
  "
query($variation_iri:String) {
  variation_descriptor_query(variation_iri: $variation_iri) {
    id: iri
    type: __typename
    ... on CategoricalVariationDescriptor {
      label
      value {
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
  (log/info :fn :clinvar-model-to-jsonld :event event)
  (let [model (::q/model event)]
    (-> model
        (jsonld/model-to-jsonld)
        (jsonld/jsonld-to-jsonld-framed (json/generate-string variation-frame))
        ; TODO may consider adding scoped context to the vrs variation object, with vocab=vrs
        (jsonld/jsonld-compact (json/generate-string (merge variation-context))))))
