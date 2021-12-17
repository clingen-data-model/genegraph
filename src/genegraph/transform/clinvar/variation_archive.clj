(ns genegraph.transform.clinvar.variation-archive-V1
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.annotate :as ann :refer [model-to-jsonld]]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-to-model
                                                        variation-geno-type
                                                        genegraph-kw-to-iri
                                                        json-prettify]]
            [genegraph.transform.clinvar.iri :as iri]
            [clojure.pprint :refer [pprint]]
            [clojure.datafy :refer [datafy]]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [io.pedestal.log :as log])
  (:import (genegraph.database.query.types RDFResource)
           (org.apache.jena.rdf.model Model ResourceFactory)
           (java.io ByteArrayOutputStream OutputStream StringWriter Writer InputStream ByteArrayInputStream)
           (java.nio.charset Charset)
           (org.apache.jena.riot.writer JsonLDWriter)
           (org.apache.jena.riot RDFFormat)
           (org.apache.jena.sparql.util Context)
           (org.apache.jena.sparql.core DatasetGraph)
           (org.apache.jena.riot.system PrefixMap PrefixMapStd)
           (org.apache.jena.sparql.core.mem DatasetGraphInMemory)
           (org.apache.jena.graph Node NodeFactory)
    ;; In Jena dependency tree
           (com.github.jsonldjava.core JsonLdOptions)
           (com.apicatalog.jsonld.document JsonDocument)
           (com.apicatalog.jsonld JsonLd)))

(def prefix-vrs-1-2-0 "https://vrs.ga4gh.org/en/1.2.0/")
(defn ns-vrs [term] (str prefix-vrs-1-2-0 term))
(defn ns-cg [term] (str iri/cgterms term))

(def variation-archive-frame
  "Frame map for VCV"
  {;"@context" {"@vocab" iri/cgterms}
   ;"@type" (ns-cg "ClinVarVCVStatement")
   "@type" "http://dataexchange.clinicalgenome.org/terms/ClinVarVCVStatement"
   })

(defn jsonld-to-jsonld-1-1-framed
  "Takes a JSON-LD (1.0 or 1.1) string and a framing string. Returns a JSON-LD 1.1 string of the
  original object, with the frame applied."
  [^String input-str ^String frame-str]
  (log/info :fn :to-jsonld-1-1-framed :input-str input-str :frame-str frame-str)
  (let [input-stream (ByteArrayInputStream. (.getBytes input-str (Charset/forName "UTF-8")))
        frame-stream (ByteArrayInputStream. (.getBytes frame-str (Charset/forName "UTF-8")))
        titanium-doc (JsonDocument/of input-stream)
        titanium-frame (JsonDocument/of frame-stream)
        framing (JsonLd/frame titanium-doc titanium-frame)]
    (-> framing .get .toString))
  )

(defn model-framed-to-jsonld
  "Takes a Jena Model object and a JSON-LD Framing 1.1 map.
  Returns a string of the model converted to JSON-LD 1.1, framed with the frame map."
  [model frame-map]
  (let [writer (JsonLDWriter. RDFFormat/JSONLD_COMPACT_PRETTY)
        sw (StringWriter.)
        ds (DatasetGraphInMemory.)
        ; TODO left blank. Seems to be fine.
        prefix-map (PrefixMapStd.)
        base-uri ""
        context (Context.)
        jsonld-options (JsonLdOptions.)
        frame-str (json/generate-string frame-map)]
    (log/trace :msg "Adding model to dataset")
    ; we don't use the graphname on export
    (.addGraph ds
               (NodeFactory/createURI "testgraphname")
               (.getGraph model))
    (log/trace :msg "Setting jsonld frame")
    ; TODO this appears to do nothing when writing jsonld
    ; maybe it affects reading, not sure why it's under JsonLDWriter then though
    (.set context JsonLDWriter/JSONLD_FRAME frame-str)
    (log/trace :msg "Setting jsonld options")
    ; TODO does nothing
    ;(.setOmitGraph jsonld-options true)
    ; TODO does nothing
    ;(.setProcessingMode jsonld-options "JSON_LD_1_1")
    (.set context JsonLDWriter/JSONLD_OPTIONS jsonld-options)
    (log/trace :msg "Writing framed jsonld")
    (.write writer sw ds prefix-map base-uri context)
    (jsonld-to-jsonld-1-1-framed (.toString sw)
                          frame-str)))

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
        _ (log/debug :fn :variation-archive-v1 :msg msg)

        vcv-iri (str iri/variation-archive (:id msg))
        vcv-statement-unversioned-iri (str vcv-iri "_statement")
        vcv-statement-iri (str vcv-statement-unversioned-iri "." (:release_date msg))
        clinvar-variation-iri (q/resource (str iri/clinvar-variation (:variation_id msg)))
        ;proposition-iri (l/blank-node)
        proposition-iri (q/resource (str vcv-statement-unversioned-iri "_proposition." (:release_date msg)))
        variation-rule-descriptor-iri (q/resource (str vcv-statement-unversioned-iri "_variation_rule_descriptor." (:release_date msg)))
        ]
    (letfn []
      [
       ; SEPIO Statement (ClinVarVCVStatement)
       ; statement: <proposition> <has confidence + direction> <strength>
       ; keyword :rdf/type are automatically converted to resources, strings not
       [vcv-statement-iri :rdf/type :sepio/Statement]
       [vcv-statement-iri :rdf/type (q/resource (ns-cg "ClinVarVCVStatement"))]
       [vcv-statement-iri :rdf/type (q/resource (ns-cg "ClinVarObject"))] ; For tracking clinvar objects
       [vcv-statement-iri :dc/has-version (:version msg)]
       [vcv-statement-iri :dc/is-version-of (q/resource (str iri/variation-archive (:id msg)))]
       [vcv-statement-iri :cg/release-date (:release_date msg)]

       [vcv-statement-iri :sepio/has-predicate (q/resource (ns-cg "has_evidence_level"))]
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
      )
    ))

;(defn variation-archive-to-jsonld [msg]
;  (let [id (format (str iri/variation-archive "%s.%s")
;                   (:id msg)
;                   (:release_date msg))]
;    (genegraph-kw-to-iri
;      (merge
;        {"@context" {"@vocab" iri/cgterms
;                     "clingen" iri/cgterms
;                     "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
;                     "clinvar" "https://www.ncbi.nlm.nih.gov/clinvar/"}
;         "@id" id
;         "@type" [:cg/ClinVarObject
;                  (str iri/cgterms "ClinVarVCVStatement")]
;         :dc/is-version-of {"@id" (str iri/variation-archive (:id msg))}
;         :dc/has-version (:version msg)
;
;         :sepio/has-subject {"@id" (str iri/clinvar-variation (:variation_id msg))}
;         :sepio/has-predicate (:interp_description msg)
;         :sepio/has-object "http://purl.obolibrary.org/obo/MONDO_0000001"
;         :sepio/date-created (:date_created msg)
;         :sepio/date-modified (:date_last_updated msg)
;
;         :sepio/qualified-contribution {:sepio/activity-date (:release_date msg)
;                                        :sepio/has-role "ArchiverRole"
;                                        :sepio/has-agent {"@id" (str iri/submitter "clinvar")}}
;
;         ; ClinGen/ClinVar additional terms (namespaced to @vocab)
;         ;"in_species"          (:species msg)
;         ;"submittedCondition"          (str iri/clinical-assertion-trait-set (:clinical_assertion_trait_set_id msg))
;
;         }
;        ; Include all other fields as extensions
;        :extensions (dissoc msg
;                            :id
;                            :version
;                            :variation_id
;                            :interp_description
;                            :date_created
;                            :date_last_updated
;                            :release_date
;                            )
;        ))))

(defn resource-to-out-triples
  "Uses steppable interface of RDFResource to obtain all the out properties and load
  them into a Model. These triples can be used as input to l/statements-to-model.
  NOTE: that only works when all the properties of the resource are in property-names.edn"
  [resource]
  (map #(cons resource %) (into {} resource)))

(defn test-query []
  (let [vcv-id "VCV000000628"
        iri-resources (q/select (str "SELECT ?iri WHERE { ?iri <http://purl.org/dc/terms/isVersionOf> ?vof . }")
                                {:vof (q/resource (str "http://dataexchange.clinicalgenome.org/terms/clinvar.variation_archive/" vcv-id))})]
    (-> iri-resources
        (->> (map resource-to-out-triples)
             (map l/statements-to-model))
        )
    ))

(defn iri-for-type
  "Given a resource iri, return the dc/is-version-of either from an in-memory model or from the connected db."
  ([rdf-type]
   (iri-for-type rdf-type nil))
  ([rdf-type model]
   (let [spql "SELECT ?iri WHERE {
                 ?iri :rdf/type ?type . } "]
     (if model
       (q/select spql {:type rdf-type} model)
       (q/select spql {:type rdf-type})))))

(def previous-resource-sparql
  "
PREFIX dc: <http://purl.org/dc/terms/>
PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
SELECT DISTINCT ?iri ?release_date WHERE {
  ?iri a ?type .
  ?iri dc:isVersionOf ?vof .
  ?iri cg:release_date ?release_date .
  FILTER(?release_date < ?input_release_date)
}
ORDER BY DESC(?release_date)
LIMIT 1")

(defn get-previous-resource
  "Attempts to obtain the resource immediately preceding the given resource :dc/is-version-of value.
  Returns the model again for use in threading."
  [iri-resource rdf-type]
  (let [;rdf-type (q/resource (ns-cg "ClinVarVCVStatement"))
        ; Get the resource of the thing this model is a version of
        ; For variation archive this is the unversioned iri
        ;iri-resource (first (iri-for-type rdf-type model))
        version-of (q/ld1-> iri-resource [:dc/is-version-of])
        ; other-versions (q/ld-> version-of [[:dc/is-version-of :<]])
        ; Query whole database for any resources which are also a version of this
        previous (q/select previous-resource-sparql
                           {:type rdf-type
                            :input_release_date (q/ld1-> iri-resource [:cg/release-date])
                            :vof version-of})]
    (cond (= 0 (count previous))
          (log/debug :fn ::get-previous-resource :msg (str "No previous resource for " iri-resource))
          (= 1 (count previous))
          (log/debug :fn ::get-previous-resource :msg (format "Previous resource for %s is %s" iri-resource (first previous)))
          (< 1 (count previous))
          (throw (ex-info "Could not determine previous resource for resource"
                          {:iri-resource iri-resource :rdf-type rdf-type})))
    (first previous)))

(defn mark-prior-replaced
  ([^Model model]
   (mark-prior-replaced model (q/resource (ns-cg "ClinVarVCVStatement"))))
  ([^Model model ^RDFResource rdf-type]
   (let [iri-resource (first (iri-for-type rdf-type model))
         previous-resource (get-previous-resource iri-resource rdf-type)]
     (when previous-resource
       (let [triples [[iri-resource :dc/replaces previous-resource]
                      [previous-resource :dc/is-replaced-by iri-resource]]]
         (.add model (l/statements-to-model triples))))
     model)))

(defn test-fn []
  (let [kafka-messages (-> "vcv-messages.txt"
                           io/file
                           slurp
                           (#(s/split % #"\n"))
                           (->> (map #(json/parse-string % true))))
        kafka-messages (filter #(= "VCV000000628" (get-in % [:content :id])) kafka-messages)
        triplesets (map #(variation-archive-v1-triples %) kafka-messages)
        models (map #(l/statements-to-model %) triplesets)]
    (map #(json-prettify %)
         (map #(model-framed-to-jsonld % variation-archive-frame) models))))

(defn test-interceptor []
  (let [kafka-messages (-> "vcv-messages.txt"
                           io/file
                           slurp
                           (#(s/split % #"\n"))
                           (->> (map #(json/parse-string % true))))
        kafka-messages (->> kafka-messages
                            (filter #(= "VCV000000628" (get-in % [:content :id])))
                            (map #(assoc % :genegraph.transform.clinvar/format :variation_archive)))
        models (map clinvar-to-model kafka-messages)]
    (map #(json-prettify %)
         (map #(model-framed-to-jsonld % variation-archive-frame) models))))

(defmethod clinvar-to-model :variation_archive [msg]
  (-> msg
      variation-archive-v1-triples
      l/statements-to-model
      mark-prior-replaced))

(defmethod model-to-jsonld :variation_archive [^Model model]
  )
