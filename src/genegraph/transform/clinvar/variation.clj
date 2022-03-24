(ns genegraph.transform.clinvar.variation
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.database.names :refer [local-property-names
                                              local-class-names
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
  (:import (org.apache.jena.rdf.model Model Statement)
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


(defn get-all-expressions
  "Attempts to return a 'canonical' expression of the variation in ClinVar.
  Takes a clinvar-raw variation message. Returns one string expression.
  Tries GRCh38, GRCh37, may add additional options in the future.

  Returns
  :expression
  :syntax
  :syntax-version

  TODO differentiate hgvs syntaxes

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
    (letfn [(hgvs-syntax-from-change
              ; Takes a change string like g.119705C>T and returns a VRS syntax string like "hgvs.g"
              [^String change]
              (if change
                (cond (.startsWith change "g.") "hgvs.g"
                      (.startsWith change "c.") "hgvs.c"
                      (.startsWith change "p.") "hgvs.p"
                      :default (let [e (ex-info "Unknown hgvs change syntax" {:change change})]
                                 (log/error :message (ex-message e) :data (ex-data e))
                                 (log/error :message "Defaulting to 'hgvs' for change" :change change)
                                 "hgvs"))
                (do (log/warn :message "No change provided, falling back to text syntax" :change change)
                    "text")))
            (nucleotide-hgvs [hgvs-obj]
              {:expression (-> hgvs-obj (get "NucleotideExpression") (get "Expression") (get "$"))
               :assembly (-> hgvs-obj (get "NucleotideExpression") (get "Assembly"))
               :syntax (hgvs-syntax-from-change (-> hgvs-obj (get "NucleotideExpression") (get "@change")))})
            (protein-hgvs [hgvs-obj]
              {:expression (-> hgvs-obj (get "ProteinExpression") (get "Expression") (get "$"))
               :syntax (hgvs-syntax-from-change (-> hgvs-obj (get "ProteinExpression") (get "@change")))})]
      (let [expressions
            (filter
              #(not (nil? %))
              (concat
                (->> hgvs-list
                     (map (fn [hgvs-obj]
                            (log/trace :fn ::get-all-expressions :hgvs-obj hgvs-obj)
                            (let [outputs (cond->
                                            []
                                            (get hgvs-obj "NucleotideExpression") (conj (nucleotide-hgvs hgvs-obj))
                                            (get hgvs-obj "ProteinExpression") (conj (protein-hgvs hgvs-obj)))]
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
        expressions))))

(defn variation-triples [msg]
  (let [msg (util/parse-nested-content msg)
        ;msg (assoc-in msg [:content :release_date] (:release_date msg))
        ;msg (assoc-in msg [:content :event_type] (:event_type msg))
        content (:content msg)

        vrd-unversioned (str (ns-cg "VariationDescriptor_") (:id content))
        vd-iri (str vrd-unversioned "." (:release_date msg))
        ;variation-rule-descriptor-iri (q/resource (str vcv-statement-unversioned-iri "_variation_rule_descriptor." (:release_date msg)))]
        clinvar-variation-iri (q/resource (str iri/clinvar-variation (:id content)))
        canonical-variation-obj (prioritized-variation-expression msg)
        canonical-variation-expression (q/resource (:expr canonical-variation-obj))
        canonical-variation-expression-type (q/resource (name (:type canonical-variation-obj)))
        ]
    (concat
      [; VRS Variation Descriptor
       ;[vrd-versioned :rdf/type (ccommon/variation-geno-type (:subclass_type content))]
       ;[vrd-versioned :rdf/type (ccommon/variation-vrs-type (:subclass_type content))]
       [vd-iri :rdf/type :vrs/CategoricalVariationDescriptor]
       [vd-iri :rdf/type (q/resource (ns-cg "ClinVarVariation"))]
       ; For tracking clinvar objects and identifying the named graph
       [vd-iri :rdf/type (q/resource (ns-cg "ClinVarObject"))]

       ; Variation Descriptor describes object: variation
       ;[vd-iri :sepio/has-object clinvar-variation-iri]
       ; TODO handle nil return value from normalization service
       ;[vd-iri :sepio/has-object (vicc/vrs-allele-for-variation (prioritized-variation-expression msg))]
       [vd-iri :sepio/has-object canonical-variation-expression]
       [canonical-variation-expression :rdf/type canonical-variation-expression-type]

       [vd-iri :dc/is-version-of (q/resource vrd-unversioned)]
       ; TODO set this to version
       ; Add clinvar's version field to extensions
       [vd-iri :owl/version-info (:release_date msg)]
       [vd-iri :cg/release-date (:release_date msg)]

       ; xrefs
       [vd-iri :vrs/xrefs (str (get prefix-ns-map "clinvar")
                               (:id content))]
       [vd-iri :vrs/xrefs (str clinvar-variation-iri)]

       ;(q/resource (str iri/clinvar-variation (:variation_id msg)))
       ; TODO reverse link to VCV? Or rely on VCV->variation that should be added by VCV
       ;;[subject-iri :rdf/type (ns-vrs "Allele")]

       ;; Variation Rule Descriptor
       ;[variation-rule-descriptor-iri :rdf/type (q/resource (ns-cg "VariationRuleDescriptor"))]
       ;[variation-rule-descriptor-iri :vrs/xref clinvar-variation-iri]]
       ]
      ; TODO Label (variant name?) should be added to this same VariationRuleDescriptor when received from variation record

      ; TODO members
      ; include the hgvs expressions
      (letfn [(make-member [node-iri expression syntax syntax-version]
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
                      [[expression-iri :vrs/syntax-version syntax-version]]))))]

        (let [members (let [exprs (get-all-expressions msg)]
                        (doall
                          (for [expr exprs]
                            (do (log/trace :fn ::variation-triples :msg "Making members" :expr expr)
                                (make-member vd-iri
                                             (:expression expr)
                                             (:syntax expr)
                                             (:syntax-version expr))))))]
          (log/trace :members members)
          (apply concat members)))

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

(defn ^Statement triple-to-statement
  "Takes a [s p o] triple such as that used by genegraph.database.load/statements-to-model.
  Returns a single Statement."
  [triple]
  (.nextStatement (.listStatements (l/statements-to-model [triple]))))

(defn add-vrs-model [model]
  (let [object-exprs (q/select "SELECT ?object WHERE { ?iri :sepio/has-object ?object }" {} model)]
    (when (< 1 (count object-exprs)) (let [e (ex-info "More than 1 object in model" {:model model
                                                                                     :object-exprs object-exprs})]
                                       (log/error :message (ex-message e) :data (ex-data e)) (throw e)))
    (let [object-expr (first object-exprs)
          object-expr-type (q/ld1-> object-expr [:rdf/type])
          vrs-obj (vicc/vrs-variation-for-expression object-expr (keyword (str object-expr-type)))]
      (if (empty? vrs-obj)
        (do (let [e (ex-info "No variation received from VRS normalization" {:fn ::add-vrs-model :object-expr object-expr})]
              (log/error :message (ex-message e) :data (ex-data e)) (throw e)))
        (let [vrs-id (get vrs-obj "_id")
              vrs-model (l/read-rdf (string->InputStream (json/generate-string vrs-obj)) {:format :json-ld})]
          (log/debug :fn ::add-vrs-model :vrs-id vrs-id :vrs-obj vrs-obj :vrs-model vrs-model)
          (let [vrs-jsonld (jsonld/model-to-jsonld vrs-model)
                vrs-jsonld-framed (jsonld/jsonld-to-jsonld-framed vrs-jsonld
                                                                  (json/generate-string
                                                                    ;{"https://vrs.ga4gh.org/type" "Allele"}
                                                                    {"@id" vrs-id}))]
            (log/debug :vrs-jsonld vrs-jsonld :vrs-jsonld-framed vrs-jsonld-framed)
            ; Add a triple linking the model iri to the vrs variation model via :sepio/has-object
            (let [variation-i vrs-id
                  _ (if (empty? variation-i) (throw (ex-info "Variation id not found" {:vrs-jsonld vrs-jsonld})))
                  i (first (q/select "SELECT ?iri WHERE { ?iri :sepio/has-object ?object }" {} model))
                  stmts [[i :sepio/has-object (q/resource variation-i)]]
                  link-model (l/statements-to-model stmts)]
              (when (empty? variation-i)
                (let [e (ex-info "Could not determine variation descriptor iri" {:model vrs-model})]
                  (log/error :message (ex-message e) :data (ex-data e)) (throw e)))
              (let [stmt-to-delete [i :sepio/has-object object-expr]]
                (log/debug :msg "Deleting previous object triple" :stmt-to-delete stmt-to-delete)
                (if (not (.contains model (triple-to-statement stmt-to-delete)))
                  (let [e (ex-info "Prior object statement not found!"
                                   {:fn ::add-vrs-model :model model :stmt-to-delete stmt-to-delete})]
                    (log/error :message (ex-message e) :data (ex-data e)) (throw e)))
                (.remove model (l/statements-to-model [stmt-to-delete])))
              (log/debug :msg "Joining model, vrs model, and linking model" :link-model link-model)
              (q/union model vrs-model link-model))
            ))))))

(defmethod common/clinvar-to-model :variation [event]
  (log/debug :fn ::clinvar-to-model :event event)
  (let [model (-> event
                  :genegraph.transform.clinvar.core/parsed-value
                  variation-triples
                  (#(do (log/debug :triples %) %))
                  l/statements-to-model
                  add-vrs-model
                  (#(do (log/debug :model %) %))
                  (#(common/mark-prior-replaced % (q/resource clinvar-variation-type))))]
    (log/debug :fn ::clinvar-to-model :model model)
    model))

(def variation-context
  {"@context"
   {; Properties
    "object" {"@id" (str (get local-property-names :sepio/has-object))
              "@type" "@id"}
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
               "@prefix" true}

    }})

(defmethod common/clinvar-model-to-jsonld :variation [event]
  (let [model (::q/model event)]
    (-> model
        (jsonld/model-to-jsonld)
        (jsonld/jsonld-to-jsonld-framed (json/generate-string variation-frame))
        ; TODO may consider adding scoped context to the vrs variation object, with vocab=vrs
        (jsonld/jsonld-compact (json/generate-string (merge variation-context))))))
