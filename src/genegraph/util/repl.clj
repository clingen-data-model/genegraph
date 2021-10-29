(ns genegraph.util.repl
  "Functions and environment useful for developing Genegraph at the REPL"
  (:require [genegraph.database.query :as q]
            [genegraph.database.load :as l]
            [genegraph.source.graphql.experimental-schema :as experimental-schema]
            [genegraph.database.instance :as db-instance]
            [genegraph.env :as env]
            [genegraph.database.util :as db-util :refer
             [tx begin-write-tx close-write-tx write-tx]]
            [genegraph.sink.stream :as stream]
            [genegraph.sink.event :as event]
            [genegraph.source.graphql.core :as gql]
            [genegraph.annotate :as ann]
            [genegraph.rocksdb :as rocks]
            [genegraph.migration :as migrate]
            [genegraph.sink.rocksdb :as rocks-sink]
            [genegraph.transform.gene-validity :as gene-validity]
            [genegraph.transform.gene-validity-refactor :as gene-validity-refactor]
            [medley.core :as medley]
            [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.spec.alpha :as spec]
            [cognitect.rebl :as rebl]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [taoensso.nippy :as nippy]
            [genegraph.database.names :as db-names]
            [genegraph.database.property-store :as property-store])
  (:import java.time.Instant
           org.apache.jena.rdf.model.AnonId))

(defn start-rebl []
  (rebl/ui))

(defn clear-named-grpahs-with-type [type-carrying-graph-name]
  (let [named-graphs (map str
                          (q/select "select ?graph where { ?graph a ?type } " 
                                    {:type type-carrying-graph-name}))]
    (doseq [graph-name named-graphs]
      (l/remove-model graph-name))))

(defn process-event-seq
  "Run event sequence through event processor"
  ([event-seq]
   (process-event-seq {} event-seq))
  ([opts event-seq]
   (write-tx
    (doseq [event event-seq]
      (event/process-event! (merge opts event))))))

(defn process-event-dry-run
  "Run event through event processor, do not create side effects"
  ([event]
   (process-event-dry-run event {}))
  ([event opts]
   (tx (event/process-event! (-> event
                                 (merge opts)
                                 (assoc ::event/dry-run true))))))

(defn use-shape [event shape-uri]
  (assoc event
         ::ann/validation-shape
         (l/read-rdf shape-uri {:format :turtle})))

(defn write-event-value-to-disk [path event]
  (with-open  [w (io/writer path)]
    (pprint (json/parse-string (::event/value event) true) w)))

(defn test-events-with-shape
  [shape-uri events]
  (let [shape (l/read-rdf shape-uri {:format :turtle})]
    (pmap #(process-event-dry-run % {::ann/validation-shape shape})
          events)))

(defn validation-frequencies
  [shape-uri events]
  (->> (test-events-with-shape shape-uri events)
       (map ::ann/did-validate)
       frequencies))

(defn first-failing-event
  [shape-uri events]
  (->> (test-events-with-shape shape-uri events)
       (filter #(= false (::ann/did-validate %)))
       first))

(defn print-model
  [event]
  (println (some-> event ::q/model q/to-turtle)))

(defn process-event-seq-dry-run
  "Run event sequence through event processor; do not perform side effects"
  [event-seq]
  (tx
   (doseq [event event-seq]
     (event/process-event! (assoc event ::event/dry-run true)))))

(defn update-topic-db
  "Run topic db through dry-run event processor, store result in db"
  [event-db]
  (doseq [event (pmap
                 process-event-dry-run
                 (rocks/entire-db-seq event-db))]
    (rocks-sink/put! event-db event)))

(defn write-events-to-db
  "Simply store events in topic db"
  [event-db events]
  (doseq [event events]
    (rocks-sink/put! event-db event)))

(defn get-graph-names []
  (tx
   (into []
         (-> db-instance/db
             .listNames
             iterator-seq))))

(def genes-for-curation-type-query
  "query ($activity: CurationActivity){
  genes(curation_activity: $activity, limit: null) {
    gene_list {
      curie
      hgnc_id
      label
    }
  }
}")


(defn genes-for-curation-type [curation-type]
  (-> (gql/gql-query genes-for-curation-type-query {:activity curation-type})
      :data
      :genes
      :gene_list))

(defn entrez-gene-set-for-curation-type [curation-type]
  (->> (genes-for-curation-type curation-type)   
       (map :curie)
       (into #{})))

(defn gene-symbol-set-for-curation-type [curation-type]
  (->> (genes-for-curation-type curation-type)   
       (map :label)
       (into #{})))

(defn hgnc-id-set-for-curation-type [curation-type]
  (->> (genes-for-curation-type curation-type)   
       (map :hgnc_id)
       (into #{})))

(defn original-actionability-genes [aci-curation-csv-path]
  (->> aci-curation-csv-path
       slurp
       csv/read-csv
       rest
       (map second)
       (map #(map str/trim (str/split % #",")))
       flatten
       (into #{})))

(defn original-dosage-genes [ftp-dosage-list-path]
  (->> (-> ftp-dosage-list-path
           slurp
           (csv/read-csv :separator \tab)
           (nthrest 6))
       (map second)
       (map #(str "NCBIGENE:" %))
       (into #{})))

(defn original-validity-genes [validity-list-path]
  (->> (-> validity-list-path
           slurp
           csv/read-csv
           (nthrest 6))
       (map second)
       (into #{})))

(defn annotate-stream-with-model [stream]
  (map (fn [event]
         (-> event
             ann/add-metadata
             ann/add-model))
       stream))

(defn annotate-stream-with-full-data [stream]
  (map (fn [event]
         (-> event
             ann/add-metadata
             ann/add-model
             ann/add-iri
             ann/add-validation
             ann/add-subjects
             ann/add-action
             ann/add-replaces))
       stream))

(defn invalid-events [events]
  (filter #(or (:exception %) (::spec/invalid %)) events))

(defn event-subjects [event]
  (when-let [subjects (::ann/subjects event)]
    (->> subjects vals flatten (into #{}))))

(defn events-about-subject [subject events]
  (filter #((event-subjects %) subject) events))

(defn events-with-value [re-to-match events]
  (filter #(re-find re-to-match (::event/value %)) events))

(defn construct-csv-for-manual-gene-review 
  "Contruct a CSV out of a set of gene IDs. Created to faciliate testing of
  newly included dosage genes in new release"
  [target-path gene-curie-set]
  (with-open [w (io/writer target-path)]
    (->> gene-curie-set
         (map (fn [gene-curie]
                (let [gene (q/resource gene-curie)]
                  [(q/ld1-> gene [:skos/preferred-label])
                   (str gene)
                   (q/curie (q/ld1-> gene [[:iao/is-about :<] :dc/is-version-of]))])))
         (cons ["symbol" "entrez gene" "ISCA id"])
         (csv/write-csv w))))


(defn keys-in [m]
  (if (map? m)
    (vec 
     (mapcat (fn [[k v]]
               (let [sub (keys-in v)
                     nested (map #(into [k] %) (filter (comp not empty?) sub))]
                 (if (seq nested)
                   nested
                   [[k]])))
             m))
    []))

(defn construct-gene-validity-events [input-file output-directory]
  (with-open [r (io/reader input-file)]
    (let [input-json (json/parse-stream r)]
      (doseq [curation input-json]
        (with-open [w (io/writer (str output-directory
                                      "/"
                                      (get curation "uuid")
                                      ".edn"))]
          (binding [*out* w]
            (pr {::event/key (get curation "uuid")
                 ::event/value (json/encode curation)
                 ::ann/format :gene-validity-raw})))))))

(defn event-seq-from-directory [directory]
  (let [files (->> directory
                  io/file
                  file-seq
                  (filter #(re-find #".edn" (.getName %))))]
    (map #(edn/read-string (slurp %)) files)))

