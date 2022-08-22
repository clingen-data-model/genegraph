(ns genegraph.transform.clinvar.common
  (:require [genegraph.database.names :refer [local-property-names local-class-names prefix-ns-map]]
            [genegraph.transform.clinvar.iri :as iri]
            [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [io.pedestal.log :as log]
            [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as s])
  (:import (genegraph.database.query.types RDFResource)
           (java.io StringWriter ByteArrayInputStream)
           (java.nio.charset Charset)
           (org.apache.jena.rdf.model Model)
           ;; (org.apache.jena.riot.writer JsonLDWriter) ; can remove? =tristan
           (org.apache.jena.sparql.util Context)
           (org.apache.jena.sparql.core.mem DatasetGraphInMemory)
           (org.apache.jena.riot RDFFormat)
           (org.apache.jena.graph NodeFactory)
           (org.apache.jena.riot.system PrefixMapStd)
           (com.github.jsonldjava.core JsonLdOptions)
           (com.apicatalog.jsonld.document JsonDocument)
           (com.apicatalog.jsonld JsonLd)))

(defmulti transform-clinvar :genegraph.transform.clinvar/format)

(defmulti clinvar-add-model :genegraph.transform.clinvar/format)

(defmulti clinvar-model-to-jsonld
  "Multimethod for ClinVar events.
          Takes an event, returns it annotated with the JSON-LD representation of the model."
  :genegraph.transform.clinvar/format)

(defmethod clinvar-model-to-jsonld :default [event]
  (log/debug :fn ::clinvar-model-to-jsonld
             :msg "No multimethod for dispatch"
             :dispatch (:genegraph.transform.clinvar/format event))
  event)

(defmulti clinvar-add-event-graphql
  "Takes an event, returns it annotated with :graphql {:query :variables}"
  :genegraph.transform.clinvar/format)

(defmethod clinvar-add-event-graphql :default [event]
  (log/debug :fn ::clinvar-add-event-graphql
             :msg "No multimethod for dispatch"
             :dispatch (:genegraph.transform.clinvar/format event))
  event)

(def clinvar-jsonld-context {"@context" {"@vocab" iri/cgterms
                                         "clingen" iri/cgterms
                                         "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
                                         "clinvar" "https://www.ncbi.nlm.nih.gov/clinvar/"}})

(defn ^String json-prettify
  [^String s]
  (json/generate-string (json/parse-string s) {:pretty true}))

(defn ^String json-unprettify
  [^String s]
  (json/generate-string (json/parse-string s)))

(defn read-csv-with-header
  "Reads a CSV file, using the first line as headers, converting each remaining
  line to a map of the headers (as keywords) to the corresponding values in each line.

  If caller closes the reader, wrap in doall to load whole file into memory."
  [reader]
  (let [lines (csv/read-csv reader)
        headers (map #(keyword %) (first lines))]
    (map #(into {} %)
         (map (fn [line] (map vector headers line))
              (rest lines)))))

(def consensus-cancer-genes-list
  (map (fn [row] {:gene_id (nth row 0)
                  :gene_symbol (nth row 1)
                  :num (Integer/parseInt (nth row 2))})
       (rest (csv/read-csv (io/reader (io/resource "consensus_cancer_genes.csv"))))))

(def consensus-cancer-genes-by-symbol
  (into {} (map (fn [{:keys [gene_id gene_symbol num]}]
                  [gene_symbol {:gene_id gene_id :num num}])
                consensus-cancer-genes-list)))

(def consensus-cancer-genes-by-id
  (into {} (map (fn [{:keys [gene_id gene_symbol num]}]
                  [gene_id {:gene_symbol gene_symbol :num num}])
                consensus-cancer-genes-list)))

(def clinvar-clinsig-map
  (doall (read-csv-with-header (io/reader (io/resource "clinvar_clinsig-map.csv")))))

(def clinvar-clinsig-map-by-clinsig
  (into {} (map (fn [{:keys [clinsig normalized group]}]
                  [clinsig {:normalized normalized :group group}])
                clinvar-clinsig-map)))

(defn normalize-clinvar-clinsig [clinsig]
  (or (get clinvar-clinsig-map-by-clinsig (s/lower-case clinsig))
      "other"))

(defn variation-vrs-type
  [clinvar-type]
  (cond (= "SimpleAllele" clinvar-type)
        :vrs/TextUtilityVariation
        (= "Haplotype" clinvar-type)
        :vrs/TextUtilityVariation
        (= "Genotype" clinvar-type)
        :vrs/TextUtilityVariation
        :default (do (log/error :msg "Unknown variation type")
                     :geno/Allele)))

(defn variation-geno-type
  [variation-type]
  (cond (= "SimpleAllele" variation-type)
        :geno/Allele
        (= "Haplotype" variation-type)
        :geno/Haplotype
        (= "Genotype" variation-type)
        :geno/Genotype
        :default (do (log/error :msg "Unknown variation type")
                     :geno/Allele)))

(defn contribution-role
  "Define contribution type for different entities"
  []
  (throw (UnsupportedOperationException. "Not yet implemented")))

(def vcv-review-status-to-evidence-strength-map
  "Maps clinvar textual VCV review status to a numerical evidence strength.
  Any value not contained here should default to a strength of 0.
  https://www.ncbi.nlm.nih.gov/clinvar/docs/review_status/"
  {"practice guideline" 4
   "reviewed by expert panel" 3
   "criteria provided, multiple submitters, no conflicts" 2
   "criteria provided, single submitter" 1
   "criteria provided, conflicting interpretations" 1
   "no assertion criteria provided" 0
   "no assertion for the individual variant" 0
   "no assertion provided" 0})
(def scv-review-status-to-evidence-strength-map
  "Maps clinvar textual SCV review status to a numerical evidence strength.
  Any value not contained here should default to a strength of 0.
  https://www.ncbi.nlm.nih.gov/clinvar/docs/review_status/"
  {"practice guideline" 4
   "reviewed by expert panel" 3
   "criteria provided, single submitter" 1
   "no assertion criteria provided" 0
   "no assertion for the individual variant" 0
   "no assertion provided" 0})

(defn genegraph-kw-to-iri
  "Uses property-names and class-names to try to resolve keywords in `m`.
  Map keys are resolved against property names. Map values are resolved against class names.

  If a map value is a map, recurses on it.

  If a keyword cannot be resolved, converts it to a string. This may be undesirable for namespaced keywords."
  [m]
  (letfn [(resolve-key [k]
            (if (keyword? k)
              (if (some #(= k %) (keys local-property-names))
                (let [mapped-k (local-property-names k)]
                  (assert (not (nil? mapped-k)) (format "%s mapped from %s was nil" k mapped-k))
                  (str mapped-k))
                (name k))
              k))
          (resolve-value [v]
            (cond (map? v) (genegraph-kw-to-iri v)
                  (vector? v) (map #(resolve-value %) v)
                  (keyword? v) (if (some #(= v %) (keys local-class-names))
                                 (let [mapped-v (local-class-names v)]
                                   (assert (not (nil? mapped-v))) (format "%s mapped from %s was nil" v mapped-v)
                                   (str mapped-v))
                                 (name v))
                  :default v))]
    (into {} (map (fn [[k v]]
                    ; Take each k and v that are keywords and try to resolve k against local-property-names, and
                    ; v against local-class-names. If keyword not in those maps, convert keyword to string (name)
                    (let [k2 (resolve-key k)
                          v2 (resolve-value v)]
                      (log/trace :mapped-values (format "%s -> %s, %s -> %s" k k2 v v2))
                      [k2 v2]))
                  m))))

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

(defn get-previous-resource
  "Attempts to obtain the resource immediately preceding the given resource :dc/is-version-of value.
  Returns the model again for use in threading."
  [iri-resource rdf-type]
  (log/debug :fn ::get-previous-resource :iri-resource iri-resource :rdf-type rdf-type)
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
  "Takes a Jena Model, and an RDFResource of the rdf/type of thing it represents.
  This function will try to find the previous RDFResource of the same rdf/type, and add two triples
  to this Model, one marking it as replacing the prior, and one marking the prior as being replaced by this."
  ([^Model model ^RDFResource rdf-type]
   (log/debug :fn ::mark-prior-replaced :model model :rdf-type rdf-type)
   (let [iri-resource (first (iri-for-type rdf-type model))
         previous-resource (get-previous-resource iri-resource rdf-type)]
     (when previous-resource
       (let [triples [[iri-resource :dc/replaces previous-resource]
                      [previous-resource :dc/is-replaced-by iri-resource]]]
         (.add model (l/statements-to-model triples))))
     model)))

(defn fields-to-extensions
  "Takes a map, converts all fields to sets of VRS Extension triples.
  `node-iri` is the subject to link each extension to."
  [node-iri m]
  (apply concat
         (for [[k v] m]
           (if (sequential? v)
             ; Take the list of lists of triples for each element, flatten one level
             (apply concat
                    (for [v1 v]
                      (fields-to-extensions node-iri {k v1})))
             (let [ext-iri (l/blank-node)]
               [[node-iri :vrs/extensions ext-iri]
                [ext-iri :rdf/type :vrs/Extension]
                [ext-iri :vrs/name (name k)]
                [ext-iri :rdf/value v]])))))

(defn fields-to-extension-maps
  "Returns a seq of Extension maps for each field in input-map.
   If a value in input-map is a seq, create an Extension for each element."
  [input-map]
  (->> (for [[k v] input-map]
         (if (sequential? v)
           (->> (for [vi v]
                  (fields-to-extension-maps {k vi}))
                (apply concat))
           [{:type "Extension"
             :name (name k)
             :value v}]))
       (apply concat)))
