(ns genegraph.util.repl
  "Functions and environment useful for developing Genegraph at the REPL"
  (:require [genegraph.database.query :as q]
            [genegraph.database.load :as l]
            [genegraph.database.instance :as db-instance]
            [genegraph.database.util :refer [tx]]
            [genegraph.sink.stream :as stream]
            [genegraph.sink.event :as event]
            [genegraph.source.graphql.core :as gql]
            [genegraph.annotate :as ann]
            [genegraph.rocksdb :as rocks]
            [genegraph.migration :as migrate]
            [genegraph.sink.rocksdb :as rocks-sink]
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
            [taoensso.nippy :as nippy])
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
  [event-seq]
  (doseq [event event-seq]
    (event/process-event! event)))

(defn process-event-seq-dry-run
  "Run event sequence through event processor; do not perform side effects"
  [event-seq]
  (doseq [event event-seq]
    (event/process-event! (assoc event ::event/dry-run true))))

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

;; (count (set/difference (original-validity-genes "/Users/thnelson/Desktop/cg-data/gene-validity.csv") (hgnc-id-set-for-curation-type "GENE_VALIDITY")))

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

;; (->> gd-stream-with-model (remove ::spec/invalid) annotate-stream-with-full-data first ::ann/iri)
;; (def gv-neo (->> "/Users/tristan/data/genegraph/2021-01-26T1745/events/gci-neo4j-archive" io/file file-seq (filter #(.isFile %)) (map #(-> % slurp edn/read-string))))

;; (-> "/Users/tristan/data/genegraph/2021-01-26T2232/events/gci-neo4j-archive/98cb808e-02d3-4378-8e6b-9b1b2883cc65.edn" slurp edn/read-string event/process-event!)
