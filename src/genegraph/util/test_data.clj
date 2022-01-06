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
            [genegraph.sink.batch :as batch]
            [genegraph.database.query :as q]
            [genegraph.database.load :as l]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def streams-to-sample
  [:gene-dosage-stage
   :actionability
   :gene-validity])

(defn add-parsed-json [curation]
  (if (= :gci-legacy (:genegraph.transform.core/format curation))
    (assoc curation ::json (json/parse-string (::event/value curation) true))
    curation))

(defn annotate-stream [stream]
  (->> stream
       (map ann/add-metadata)
       (map ann/add-model)
       (map ann/add-iri)
       (map ann/add-validation)       
       (map ann/add-subjects)
       (map ann/add-action)
       (map add-parsed-json)))

(defn top-curations-with-updates 
  "Take top curation that has a published update."
  [stream]
  (->> stream (group-by ::ann/iri) vals (sort-by count) (take-last 1)))

(defn add-hgnc-gene-data [curation-events]
  "get hgnc data related to selected genes"
  (with-open [r (io/reader (str base/target-base "hgnc.json"))]
    (let [genes (:curated-genes curation-events)
          hgnc-gene (get-in (json/parse-stream r true) [:response :docs])
          entrez-ids (into #{} (map #(re-find #"\d*$" %) genes))
          selected-hgnc-genes (filter #(entrez-ids (:entrez_id %)) hgnc-gene)
          random-extra-genes (take 3 (remove #(entrez-ids (:entrez_id %)) hgnc-gene))
          reconstructed-hgnc-genes {:response {:docs (concat selected-hgnc-genes random-extra-genes)}}]
      (merge curation-events
             {:random-uncurated-genes (map #(str "https://www.ncbi.nlm.nih.gov/gene/" (:entrez_id %))
                                           random-extra-genes)
              :hgnc-genes {::event/key "https://www.genenames.org/"
                           ::event/value (json/generate-string reconstructed-hgnc-genes)
                           ::ann/iri "https://www.genenames.org/"
                           ::ann/format :hgnc-genes
                           ::random-extra-genes (map #(str "https://www.ncbi.nlm.nih.gov/gene/" (:entrez_id %))
                                                     random-extra-genes)}}))))

(def construct-mondo-subgraph
  (q/create-query 
   (str 
    "construct {?s ?p ?o} where { " 
    " ?disease <http://www.w3.org/2000/01/rdf-schema#subClassOf>* ?s ."
    " GRAPH <http://purl.obolibrary.org/obo/mondo.owl> { ?s ?p ?o } } ")))

(def construct-direct-disease-relationships
  (q/create-query "construct where {?disease ?p ?o}"))

(defn construct-mondo-subgraph-of-diseases [diseases]
  (reduce 
   q/union
   (q/empty-model)
   (map (fn [disease] 
          (let [params {:disease disease}]
            (q/union (construct-mondo-subgraph params)
                     (construct-direct-disease-relationships params))))
        diseases)))

(defn add-mondo-data [curation-events]
  "get subset of mondo data related to selected diseases"
  (let [diseases (:curated-diseases curation-events)
        selected-uncurated-diseases 
        (q/select (str 
                   "select ?disease where "
                   "{ ?disease "
                   " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                   " <http://purl.obolibrary.org/obo/MONDO_0000001> . "
                   " FILTER ( ?disease NOT IN ( "
                   (str/join ", " (map #(str "<" % ">") diseases))
                   " ) ) } limit 3") )
        result (q/to-turtle 
                (q/union (construct-mondo-subgraph-of-diseases (map q/resource diseases))
                         (construct-mondo-subgraph-of-diseases selected-uncurated-diseases)))]
    (merge curation-events
           {:random-uncurated-diseases (map str selected-uncurated-diseases)
            :mondo-diseases {::event/key "http://purl.obolibrary.org/obo/mondo.owl"
                             ::ann/iri "http://purl.obolibrary.org/obo/mondo.owl"
                             ::event/value result
                             ::ann/format :mondo
                             ::random-extra-diseases (map str selected-uncurated-diseases)}})))

(defn add-base-data [curation-events]
  (let [base-data (-> "test_data/base_events.edn" io/resource slurp edn/read-string)]
    (assoc curation-events
           :base-data
           (mapv #(assoc % ::event/value (slurp (str base/target-base "/" (:target %)))) base-data))))


(defn select-published-curations [curation-sequence]
  (filter #(= "Publish" (get-in % [::json :statusPublishFlag])) curation-sequence))

(defn gene-validity-update-sequence 
  "Identify a sequence of gene-validity curations where there is a straightfoward update of one cuation"
  [curation-sequence]
  (let [published-curations (select-published-curations curation-sequence)]
    (when (< 1 (count published-curations))
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


(defn add-gene-validity-update-sequence [curation-sequence]
  (-> curation-sequence
      (assoc :gene-validity-update-sequence 
             (some gene-validity-update-sequence 
                   (:gene-validity-stream curation-sequence)))
      (update :curation-keys conj :gene-validity-update-sequence)))


(defn gene-validity-unpublish-sequence [curation-sequence]
  (let [publish-curation (first (filter #(= :publish (::ann/action %)) curation-sequence))
        unpublish-curation (first (filter #(= :unpublish (::ann/action %)) curation-sequence))]
    (when (and publish-curation unpublish-curation)
      [publish-curation
       unpublish-curation])))

(defn add-gene-validity-unpublish-sequence [curation-sequence]
  (-> curation-sequence
      (assoc :gene-validity-unpublish-sequence 
             (some gene-validity-unpublish-sequence
                   (:gene-validity-stream curation-sequence)))
      (update :curation-keys conj :gene-validity-unpublish-sequence)))



(defn stream-data [stream]
  (->> (stream/topic-data stream)
       annotate-stream
       (group-by ::ann/iri)
       vals))

(defn flattened-curation-events [curation-events]
  (->> (select-keys curation-events (:curation-keys curation-events)) vals flatten))

(defn curated-genes
  "select the genes from curation events"
  [events]
  (into #{} (mapcat #(get-in % [::ann/subjects :gene-iris]) events)))

(defn add-curated-genes [curation-events]
  (assoc curation-events 
         :curated-genes
         (curated-genes (flattened-curation-events curation-events))))

(defn curated-diseases
  "select the genes from curation events"
  [events]
  (into #{} (mapcat #(get-in % [::ann/subjects :disease-iris]) events)))

(defn add-curated-diseases [curation-events]
  (assoc curation-events 
         :curated-diseases
         (curated-diseases (flattened-curation-events curation-events))))

(defn event-keys 
  "remove all keys for an event except for the ones to write to the event package"
  [event]
  (select-keys event [::event/key ::event/value ::ann/format]))

(defn strip-annotation-from-curation-events [curation-events]
  (reduce #(assoc %1 %2 (map event-keys (get %1 %2)))
          curation-events
          (:curation-keys curation-events)))

(defn add-gci-express-update-sequence [curation-events]
  (let [gci-express-by-subjects (reduce #(assoc %1 (::ann/subjects %2) %2)
                                        {}
                                        (:gci-express-data curation-events))
        gci-update-of-gci-express (first (filter #(get gci-express-by-subjects (::ann/subjects %))
                                                 (flatten (:gene-validity-stream curation-events))))]
    (-> curation-events 
        (assoc :gci-express-update-sequence
               [(get gci-express-by-subjects  (::ann/subjects gci-update-of-gci-express))
                gci-update-of-gci-express])
        (update :curation-keys conj :gci-express-update-sequence))))

(defn construct-test-data [event-streams]
  (-> event-streams
      add-gene-validity-update-sequence
      add-gene-validity-unpublish-sequence
      add-gci-express-update-sequence
      add-curated-genes
      add-curated-diseases
      add-hgnc-gene-data
      add-mondo-data
      add-base-data
      strip-annotation-from-curation-events))

(defn write-event-sequence
  "write event sequence to target dir"
  [event-sequence target-file]
  (with-open [w (io/writer target-file)]
    (binding [*out* w]
      (pr event-sequence))))

(defn construct-gci-express-data []
  (annotate-stream
   (map (fn [curation]
          {::event/value curation
           ::ann/format :gci-express})
        (-> (batch/target-path "/gci-express-json") 
            slurp
            (json/parse-string true)))))

(defn write-test-data [target-file]
  (-> {:gene-validity-stream (stream-data :gene-validity)
       :gci-express-data (construct-gci-express-data)}
       construct-test-data
       (dissoc :gene-validity-stream :gci-express-data)
       (write-event-sequence target-file)))

(defn get-test-events []
  (-> "test_data/test_events.edn" io/resource slurp edn/read-string))
