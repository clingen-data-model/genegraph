(ns genegraph.transform.clinvar.common
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [genegraph.database.load :as l]
            [genegraph.database.names :as names]
            [genegraph.database.query :as q]
            [genegraph.database.query.types :as types]
            [genegraph.transform.clinvar.iri :as iri]
            [io.pedestal.log :as log])
  (:import (genegraph.database.query.types RDFResource)
           (org.apache.jena.rdf.model Model)))

(defmulti transform-clinvar :genegraph.transform.clinvar/format)

(defmulti clinvar-add-model :genegraph.transform.clinvar/format)

(defmulti clinvar-add-data :genegraph.transform.clinvar/format)

(defmethod clinvar-add-data :default [event]
  (log/debug :fn :clinvar-model-to-jsonld
             :msg "No multimethod for dispatch"
             :dispatch (:genegraph.transform.clinvar/format event))
  event)

(defmulti clinvar-model-to-jsonld
  "Multimethod for ClinVar events. Takes an event, returns it annotated
  with the JSON-LD representation of the model."
  :genegraph.transform.clinvar/format)

(defmethod clinvar-model-to-jsonld :default [event]
  (log/debug :fn :clinvar-model-to-jsonld
             :msg "No multimethod for dispatch"
             :dispatch (:genegraph.transform.clinvar/format event))
  event)

(defmulti clinvar-add-event-graphql
  "Takes an event, returns it annotated with :graphql {:query :variables}"
  :genegraph.transform.clinvar/format)

(defmethod clinvar-add-event-graphql :default [event]
  (log/debug :fn :clinvar-add-event-graphql
             :msg "No multimethod for dispatch"
             :dispatch (:genegraph.transform.clinvar/format event))
  event)

(def clinvar-jsonld-context {"@context" {"@vocab" iri/cgterms
                                         "clingen" iri/cgterms
                                         "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
                                         "clinvar" "https://www.ncbi.nlm.nih.gov/clinvar/"}})

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

(defn load-csv-resource [file-name]
  (-> file-name io/resource io/reader read-csv-with-header doall))

(def clinvar-clinsig-normalized
  "Seq of maps with keys :scv_term :normalized :label"
  (load-csv-resource "clinvar_clinsig_normalized.csv"))

(def clinvar-clinsig-classes
  (load-csv-resource "clinvar_clinsig_classes.csv"))

(def normalize-clinsig-map
  (into {} (map (fn [{:keys [scv_term normalized label]}]
                  [scv_term label])
                clinvar-clinsig-normalized)))

(def normalize-clinsig-codes-map
  (into {} (map (fn [{:keys [scv_term normalized label]}]
                  [scv_term normalized])
                clinvar-clinsig-normalized)))

(def clinsig-class-map
  "Map of normalized clinsig terms to clinsig classes"
  (into {} (map (fn [{:keys [code label clinvar_prop_type]}]
                  [label clinvar_prop_type])
                clinvar-clinsig-classes)))


;;;;; BEGIN REMOVE
;; This clinvar_clinsig-map.csv file should be obsoleted and removed.
;; But it is referenced in some functions which implement functionality that may
;; be re-incorporated soon. When that happens it should be refactored to remove these
(def clinvar-clinsig-map
  (doall (read-csv-with-header (io/reader (io/resource "clinvar_clinsig-map.csv")))))

(def clinvar-clinsig-map-by-clinsig
  (into {} (map (fn [{:keys [clinsig normalized group]}]
                  [clinsig {:normalized normalized :group group}])
                clinvar-clinsig-map)))

(defn normalize-clinvar-clinsig [clinsig]
  (or (get clinvar-clinsig-map-by-clinsig (str/lower-case clinsig))
      "other"))
;;;;; END REMOVE

(defn variation-vrs-type
  [clinvar-type]
  (cond (= "SimpleAllele" clinvar-type)
        :vrs/TextUtilityVariation
        (= "Haplotype" clinvar-type)
        :vrs/TextUtilityVariation
        (= "Genotype" clinvar-type)
        :vrs/TextUtilityVariation
        :else (do (log/error :msg "Unknown variation type")
                  :geno/Allele)))

(defn variation-geno-type
  [variation-type]
  (cond (= "SimpleAllele" variation-type)
        :geno/Allele
        (= "Haplotype" variation-type)
        :geno/Haplotype
        (= "Genotype" variation-type)
        :geno/Genotype
        :else (do (log/error :msg "Unknown variation type")
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
              (if (some #(= k %) (keys names/local-property-names))
                (let [mapped-k (names/local-property-names k)]
                  (assert (not (nil? mapped-k)) (format "%s mapped from %s was nil" k mapped-k))
                  (str mapped-k))
                (name k))
              k))
          (resolve-value [v]
            (cond (map? v) (genegraph-kw-to-iri v)
                  (vector? v) (map #(resolve-value %) v)
                  (keyword? v) (if (some #(= v %) (keys names/local-class-names))
                                 (let [mapped-v (names/local-class-names v)]
                                   (assert (not (nil? mapped-v)) (format "%s mapped from %s was nil" v mapped-v))
                                   (str mapped-v))
                                 (name v))
                  :else v))]
    (into {} (map (fn [[k v]]
                    ;; Take each k and v that are keywords and try to resolve k against local-property-names, and
                    ;; v against local-class-names. If keyword not in those maps, convert keyword to string (name)
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
        ;: Get the resource of the thing this model is a version of
        ;: For variation archive this is the unversioned iri
        ;iri-resource (first (iri-for-type rdf-type model))
        version-of (q/ld1-> iri-resource [:dc/is-version-of])
        ;: other-versions (q/ld-> version-of [[:dc/is-version-of :<]])
        ;: Query whole database for any resources which are also a version of this
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
             ;; Take the list of lists of triples for each element, flatten one level
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
   If a value in input-map is a seq, and expand-seqs? is true,
   create an Extension for each element.
   example:
   (fields-to-extension-maps
    {:a :A :b :B :d [:D1 :D2]}
    {:expand-seqs? true})"
  ([input-map] (fields-to-extension-maps input-map {}))
  ([input-map {:keys [expand-seqs?]}]
   (letfn [(make-ext [agg [k v]]
             (if (and (sequential? v) expand-seqs?)
               (reduce make-ext agg (map #(vector k %) v)) ;; reduce pairs of k with each v
               (conj agg {:type "Extension" :name (name k) :value v})))]
     (reduce make-ext [] input-map))))

(defn replace-kvs
  "Recursively replace keys in input-map and its values by applying kv-mutate-fn.
   If kv-mutate-fn returns nil, the kv pair is removed.
   Recurses through all maps either in values or in vector/lists in values."
  [input-map kv-mutate-fn]
  (if (map? input-map)
    (into {} (map (fn [[k v]]
                    (when-let [[k1 v1] (kv-mutate-fn k v)]
                      (cond
                        (map? v1) [k1 (replace-kvs v1 kv-mutate-fn)]
                        (sequential? v1) [k1 (map #(replace-kvs % kv-mutate-fn) v1)]
                        :else [k1 v1])))
                  input-map))
    input-map))

(defn map-rdf-resource-values-to-str
  [input-map]
  (letfn [(mutator [k v]
            (if (= (.getCanonicalName genegraph.database.query.types.RDFResource)
                   (-> v class (#(when % (.getCanonicalName %))))
                   #_(.getCanonicalName (class v)))
              [k (str v)]
              [k v]))]
    (replace-kvs input-map mutator)))

(defn ^String un-namespace-term
  "Takes a potentially expanded namespaced term, and returns the term without the namespace.
   e.g. http://example.org/MyTerm -> MyTerm.
   Uses namespaces from namespaces.edn.

   TODO only some of these are in use in this module. It performs pretty well
   but if we could check just some namespaces, might be faster."
  [term]
  (let [term (str term)
        namespaces (keys names/ns-prefix-map)]
    (or (some #(when (.startsWith term %)
                 (subs term (.length %)))
              namespaces)
        term)))

(defn ^String un-prefix-term
  "Takes a term and if prefixed with a known prefix (in namespaces.edn), removes it"
  [term]
  (let [term (str term)
        prefixes (map #(str % ":") (keys names/prefix-ns-map))]
    (or (some #(when (.startsWith term %)
                 (subs term (.length %)))
              prefixes)
        term)))

(defn map-unnamespace-keys
  "Recursively apply un-namespace-term to a map"
  [input-map]
  (letfn [(mutator [k v]
            (un-namespace-term (str k)))]
    (replace-kvs input-map mutator)))

(defn map-unnamespace-property-kw-keys
  "Recursively look up keys in property-names, if there, apply un-namespace-term to its value"
  [input-map]
  (letfn [(mutator [k v]
            (let [property (get names/local-property-names k)]
              (if property
                (let [unnamespaced (un-namespace-term (str (get names/local-property-names k)))]
                  (log/info :property property
                            :unnamespaced unnamespaced)
                  [unnamespaced v])
                [k v])))]
    (replace-kvs input-map mutator)))

(defn map-unnamespace-values
  "Recursively apply un-namespace-term to a map"
  ([input-map]
   (map-unnamespace-values input-map nil))
  ([input-map fields-to-process]
   (letfn [(mutator [k v]
             (if (and (or (nil? fields-to-process)
                          (contains? fields-to-process k))
                      (string? v))
               [k (un-namespace-term v)]
               [k v]))]
     (replace-kvs input-map mutator))))

(defn ^String compact-namespaced-term
  "Performs same logic as un-namespace-term, but replaces the namespace with
  the defined prefix instead of removing it."
  [^String term]
  (let [term (str term)
        namespaces (keys names/ns-prefix-map)]
    (or (some #(when (.startsWith term %)
                 (str (get names/ns-prefix-map %)
                      ":"
                      (subs term (.length %))))
              namespaces)
        term)))

(defn map-compact-namespaced-values
  [input-map]
  (let [fields-to-process #{:id :subject_descriptor :is_version_of}]
    (letfn [(mutator [k v]
              (if (and (contains? fields-to-process k)
                       (string? v))
                [k (compact-namespaced-term v)]
                [k v]))]
      (replace-kvs input-map mutator))))

(defn resource-to-out-triples
  "Uses steppable interface of RDFResource to obtain all the out properties and load
  them into a Model. These triples can be used as input to l/statements-to-model.
  NOTE: that only works when all the properties of the resource are in property-names.edn"
  [resource]
  ;: [k v] -> [r k v]
  (map #(cons resource %) (into {} resource)))

(defn is-RDFResource? [thing]
  (= (.getCanonicalName genegraph.database.query.types.RDFResource)
     (some-> thing class .getCanonicalName)))

(defn is-blank-node? [^RDFResource resource]
  (nil? (-> resource types/as-jena-resource .getURI)))

(defn map-pop-out-lone-seq-values
  "Returns INPUT-MAP with any values that are seqs of 1 element
   replaced with that 1 element. Not recursive."
  [input-map]
  (into {} (map (fn [[k v]]
                  (if (and (sequential? v) (= 1 (count v)))
                    [k (first v)]
                    [k v]))
                input-map)))

(defn resource-out-map
  "Returns a map of all outgoing [pred obj] triples from RESOURCE.
   If multiple out triples have same pred, puts the objs in a vector."
  [resource]
  ;; Uses Datafiable interface of RDFResource
  (let [tuples2 (into [] resource)
        result (->> tuples2
                    (sort-by first)
                    (partition-by first)
                    (map (fn [group] [;; Property kw for the group
                                      (-> group first first)
                                      ;; Elements in the group
                                      (mapv second group)]))
                    (into {})
                    map-pop-out-lone-seq-values)]
    result))

(defn rdf-select-tree
  "Recursively selects outgoing triples from RDFResources in objects of triples, starting from a root RDFResource.
   If root-resource is not an RDFResource, returns it.
   NOTE: do not use this on a resource which may have an edge cycle. Does not have cycle detection right now."
  [root-resource]
  (if (is-RDFResource? root-resource)
    (let [outgoing (resource-out-map root-resource)]
      (log/info :fn :rdf-select-tree
                :root-resource root-resource
                :resource-class (class root-resource)
                :jena-resource (types/as-jena-resource root-resource)
                :uri (.getURI (types/as-jena-resource root-resource)))
      (if (not-empty outgoing)
        (merge {}
               (when (not (is-blank-node? root-resource))
                 {:id (str root-resource)})
               (into {} (map (fn [[k v]]
                               (cond
                                 (sequential? v) [k (map rdf-select-tree v)]
                                 :else [k (rdf-select-tree v)]))
                             outgoing)))
        ;; was a resource, but no triples
        root-resource))
    ;; not a resource
    root-resource))

(defn model-to-triples
  "Returns a seq of all [s p o] triples in the model. Unordered."
  [^Model model]
  (-> model .listStatements iterator-seq
      (->> (map #(vector (.getSubject %)
                         (.getPredicate %)
                         (.getObject %))))))

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

(defn map-remove-nil-values
  "Remove fields in map whose value is nil. Recursively with replace-kvs."
  [input-map]
  (letfn [(mutator [k v]
            (when (not (nil? v))
              (vector k v)))]
    (replace-kvs input-map mutator)))

(defn with-retries
  "Tries to execute body-fn retry-count times."
  [retry-count retry-interval-ms body-fn]
  (loop [remaining-retries retry-count]
    (let [[ran? ret]
          (try [true (body-fn)]
               (catch Exception e
                 (if (= 0 remaining-retries)
                   (do (log/error :fn :with-retries :msg "Retry limit exceeded")
                       (throw e))
                   (do (log/info :fn :with-retries
                                 :msg (format "body-fn failed, trying again in %s ms"
                                              retry-interval-ms))
                       (Thread/sleep retry-interval-ms)))))]
      (if ran?
        ret
        (recur (dec retry-count))))))
