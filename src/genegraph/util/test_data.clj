(ns genegraph.util.test-data
  "Generate sample data using a slice of production data. 
  Will serve as the basis for unit testing. While the data generated from this should work
  from an empty Jena DB (for testing, at least), these functions require a running and competent
  Jena instance to work."
  (:require [genegraph.sink.stream :as stream]
            [genegraph.sink.event :as event]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [genegraph.annotate :as ann]
            [genegraph.sink.base :as base]
            [genegraph.database.query :as q]
            [genegraph.database.load :as l]
            [clojure.edn :as edn]))

(def streams-to-sample
  [:gene-dosage-stage
   :actionability
   :gene-validity])

(defn add-parsed-json [curation]
  (assoc curation ::json (json/parse-string (::event/value curation) true)))

(defn annotate-stream [stream]
  (->> stream
       (map ann/add-metadata)
       (map ann/add-model)
       (map ann/add-iri)
       (map ann/add-validation)       
       (map ann/add-subjects)
       (map add-parsed-json)))

(defn top-curations-with-updates 
  "Take top curation that has a published update."
  [stream]
  (->> stream (group-by ::ann/iri) vals (sort-by count) (take-last 1)))

(defn curated-genes
  "select the genes from curation events"
  [events]
  (into #{} (mapcat #(get-in % [::ann/subjects :gene-iris]) events)))

(defn curated-diseases
  "select the genes from curation events"
  [events]
  (into #{} (mapcat #(get-in % [::ann/subjects :disease-iris]) events)))

(defn hgnc-gene-data [genes]
  "get hgnc data related to selected genes"
  (with-open [r (io/reader (str (base/target-base) "hgnc.json"))]
    (let [hgnc-gene (get-in (json/parse-stream r true) [:response :docs])
          entrez-ids (into #{} (map #(re-find #"\d*$" %) genes))
          selected-hgnc-genes (filter #(entrez-ids (:entrez_id %)) hgnc-gene)
          random-extra-genes (take 3 (remove #(entrez-ids (:entrez_id %)) hgnc-gene))
          reconstructed-hgnc-genes {:response {:docs (concat selected-hgnc-genes random-extra-genes)}}]
      {::event/key "https://www.genenames.org/"
       ::event/value (json/generate-string reconstructed-hgnc-genes)
       ::ann/iri "https://www.genenames.org/"
       ::ann/format :hgnc-genes
       ::random-extra-genes (map #(str "https://www.ncbi.nlm.nih.gov/gene/" (:entrez_id %))
                                 random-extra-genes)})))

(def construct-mondo-subgraph
  (q/create-query 
   (str 
    "construct {?s ?p ?o} where { " 
    " ?disease <http://www.w3.org/2000/01/rdf-schema#subClassOf>* ?s ."
    " GRAPH <http://purl.obolibrary.org/obo/mondo.owl> { ?s ?p ?o } } ")))

(def construct-direct-disease-relationships
  (q/create-query "construct where {?disease ?p ?o}"))

(defn mondo-data [diseases]
  "get subset of mondo data related to selected diseases"
  (let [result 
        (q/to-turtle 
         (reduce 
          q/union
          (q/empty-model)
          (map (fn [disease] 
                 (let [params {:disease (q/resource disease)}]
                   (q/union (construct-mondo-subgraph params)
                            (construct-direct-disease-relationships params))))
               diseases)))]
    {::event/key "http://purl.obolibrary.org/obo/mondo.owl"
     ::ann/iri "http://purl.obolibrary.org/obo/mondo.owl"
     ::event/value result
     ::ann/format :mondo}))

(defn base-data []
  (let [base-data (-> "test_data/base_events.edn" io/resource slurp edn/read-string)]
    (mapv #(assoc % ::event/value (slurp (str (base/target-base) "/" (:target %)))) base-data)))


(defn select-published-curations [curation-sequence]
  (filter #(= "Publish" (get-in % [::json :statusPublishFlag])) curation-sequence))

(defn event-keys 
  "remove all keys for an event except for the ones to write to the event package"
  [event]
  (select-keys event [::event/key ::event/value ::ann/format]))

(defn gene-validity-update-sequence 
  "Identify a sequence of gene-validity curations where there is a straightfoward update of one cuation"
  [curation-sequence]
  (let [published-curations (select-published-curations curation-sequence)]
    (when (< 1 (count published-curations))
      (println (count published-curations))
      (doseq [curation published-curations]
        (println (get-in curation [::json :scoreJson :summary :FinalClassificationDate])))
      (let [first-published-curation (first published-curations)
            first-published-date (-> first-published-curation ::json :scoreJson :summary :FinalClassificationDate)
            second-published-curation (->> (rest published-curations)
                                           (filter (fn [curations]
                                                     (some #(not= first-published-date 
                                                                  (get-in % [::json
                                                                             :scoreJson
                                                                             :summary
                                                                             :FinalClassificationDate]))
                                                           curations)))
                                           first)]
        (when second-published-curation
          [first-published-curation
           second-published-curation])))))

(defn is-gene-validity-unpublish-sequence []
  ())

(defn is-gene-validity-invalid []
  ())

(defn construct-event-sequence 
  "build sequence of events necessary for populating and evaluating database"
  [curation-events]
  (let [single-gv-curation (-> curation-events first first)
        gv-sequence-with-update (some gene-validity-update-sequence curation-events)
        all-published-curation-events (conj gv-sequence-with-update single-gv-curation)
        genes (curated-genes all-published-curation-events)
        diseases (curated-diseases all-published-curation-events)
        hgnc-genes (hgnc-gene-data genes)]
    {:curated-genes genes
     :random-uncurated-genes (::random-extra-genes hgnc-genes)
     :curated-diseases diseases
     :base-data (base-data)
     :hgnc-genes hgnc-genes
     :mondo-diseases (mondo-data diseases)
     :publish-gv-curation (event-keys single-gv-curation)
     :gene-validity-update-sequence (map event-keys gv-sequence-with-update)}))

(defn stream-data [stream]
  (->> (stream/topic-data stream)
       annotate-stream
       (group-by ::ann/iri)
       vals))

(defn write-event-sequence
  "write event sequence to target dir"
  [target-file event-sequence]
  (with-open [w (io/writer target-file)]
    (binding [*out* w]
      (pr event-sequence))))

(defn write-test-data [target-file]
  (->> (stream-data :gene-validity)
       construct-event-sequence
       (write-event-sequence target-file)))
