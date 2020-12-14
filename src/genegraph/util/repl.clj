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
            [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.spec.alpha :as spec]
            [cognitect.rebl :as rebl]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]))

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

(defn annotate-stream [stream]
  (map (fn [event]
         (-> event
             ann/add-metadata
             ann/add-model
             ;; ann/add-iri
             ;; ann/add-validation
             ;; ann/add-subjects
             ;; ann/add-action
             ;; ann/add-replaces
             ))
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
