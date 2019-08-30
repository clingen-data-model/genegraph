(ns clingen-search.sink.stream
  (:require [clingen-search.database.load :as db]
            [clingen-search.transform.core :refer [transform-doc]]
            [clingen-search.env :as env]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [mount.core :refer [defstate]]
            [clojure.edn :as edn]
            [io.pedestal.log :as log]
            [clingen-search.transform.actionability]
            [clojure.string :as s]
            [clojure.data :as data])
  (:import java.util.Properties
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord
            ConsumerRecords]
           [org.apache.kafka.common PartitionInfo TopicPartition]
           java.time.Duration))

(def offset-file (str env/data-vol "/partition_offsets.edn"))

(def current-offsets (atom {}))



(def client-properties
  {"bootstrap.servers" env/dx-host
   "group.id" env/dx-group
   "enable.auto.commit" "false"
   "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
   "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
   "security.protocol" "SSL"
   ;;"ssl.truststore.location" "keys/serveur.truststore.jks"
   "ssl.truststore.location" env/dx-truststore
   "ssl.truststore.password" env/dx-key-pass
   "ssl.keystore.location" env/dx-keystore
   "ssl.keystore.password" env/dx-key-pass
   "ssl.key.password" env/dx-key-pass})

;; (def topic-handlers
;;   {"actionability" :actionability-v1
;;    "gene_dosage_beta" :rdf})


(def topic-handlers
  {"actionability" {:format :actionability-v1}
   "gene_dosage_beta" {:format :rdf, :reader-opts {:format :json-ld}}})




;; Java Properties object defining configuration of Kafka client
(defn- client-configuration 
  "Create client "
  []
  (let [props (new Properties)]
    (doseq [p client-properties]
      (.put props (p 0) (p 1)))
    props))

(defn- create-kafka-consumer
  []
  (let [props (client-configuration)]
    (new KafkaConsumer props)))

(defn- topic-partitions [c topic]
  (let [partition-infos (.partitionsFor c topic)]
    (map #(TopicPartition. (.topic %) (.partition %)) partition-infos)))

(defn- poll-once 
  ([c] (-> c (.poll (Duration/ofMillis 100)) .iterator iterator-seq)))

(defn- assign-topic! [consumer topic]
  (let [tp (topic-partitions consumer topic)]
    (.assign consumer tp)
    (doseq [part tp]
      (if-let [offset (get @current-offsets [topic (.partition part)])]
        (.seek consumer part offset)
        (.seekToBeginning consumer [part])))))

(defn import-record! [record]
  (try
    (let [iri (-> record .value json/parse-string (get "iri"))
          doc-model (transform-doc (assoc (get topic-handlers (.topic record)) :name iri)
                                   (.value record))]
      (log/info :fn :import-record! :msg :importing :iri iri)
      (db/load-model doc-model iri))
    (catch Exception e 
      (.printStackTrace e)
      (log/warn :fn :import-record!
                :topic (.topic record)
                :partition (.partition record)
                :offset (.offset record)
                :record record
                :msg (str e)))))

(defn update-offsets! [consumer tps]
  (doseq [tp tps]
    (swap! current-offsets assoc [(.topic tp) (.partition tp)] (.position consumer tp)))
  (spit offset-file (pr-str @current-offsets)))

(def run-consumer (atom true))

(defn read-offsets! [] 
  (if (.exists (io/as-file offset-file))
    (reset! current-offsets (-> offset-file slurp edn/read-string))
    (reset! current-offsets {})))

(def end-offsets (atom {}))

(defn read-end-offsets! [consumer topic-partitions]
  (let [kafka-end-offsets (.endOffsets consumer topic-partitions)
        end-offset-map (reduce (fn [acc [k v]]
                                 (assoc acc [(.topic k) (.partition k)] v))
                               {} kafka-end-offsets)]
    (reset! end-offsets end-offset-map)))

(defn up-to-date? 
  "Returns true if all partitions of all topics subscribed to have had messages
  consumed up to the latest offset when the consumer was started."
  []
  (when (= (set (keys @current-offsets)) (set (keys @end-offsets)))
    (let [partition-is-up-to-date? (merge-with <= @end-offsets @current-offsets)]
      (if (some false? (vals partition-is-up-to-date?))
        false
        true))))

(defn subscribe!
  "Start a Kafka consumer listening to topics in topic-list
  Messages are transformed to RDF, if needed, and imported into triplestore"
  [topic-list]
  (read-offsets!)
  (with-open [consumer (create-kafka-consumer)]
    (doseq [topic topic-list]
      (println "assigning " topic)
      (assign-topic! consumer topic))
    (let [tps (mapcat #(topic-partitions consumer %) topic-list)]
      (read-end-offsets! consumer tps)
      (while @run-consumer
        (doseq [record (poll-once consumer)]
          (import-record! record))
        (update-offsets! consumer tps)))))

(defstate consumer-thread
  :start (let [topics (s/split env/dx-topics #";")
               t (Thread. (partial subscribe! topics))]
           (reset! run-consumer true)
           (.start t)
           t)
  :stop  (reset! run-consumer false))

(defn long-poll [c]
  (-> c (.poll (Duration/ofMillis 2000)) .iterator iterator-seq))

(defn topic-data [topic]
  (with-open [c (create-kafka-consumer)]
    (let [tp (topic-partitions c topic)]
      (.assign c tp)
      (.seekToBeginning c tp)
      (loop [records (long-poll c)]
        (let [addl-records (long-poll c)]
          (if-not (seq addl-records)
            records
            (recur (concat records addl-records))))))))

(defn consumer-record-to-clj [consumer-record]
  (try 
    (-> consumer-record .value json/parse-string)
    (catch Exception e (println "invalid JSON"))))

(defn topic-to-disk [topic folder]
  (let [records (topic-data topic)]
    (println (count records))
    (doseq [record-payload records]
      (if-let [record (consumer-record-to-clj record-payload)]
        
        (let  [id (re-find #"[A-Za-z0-9-]+$" (or (str (get record "iri") ) (get-in record ["interpretation" "id"])))
               wg (get-in record ["affiliations" 0 "id"])]
              (spit (str folder "/" id ".json") (.value record-payload)))))))

(defn load-local-data 
  "Treat all files stored in dir as loadable data in json-ld form, load them
  into base datastore"
  [dir]
  (let [files (filter #(.isFile %) (-> dir io/file file-seq))]
    (doseq [file files]
      (println "importing " (.getName file))
      (with-open [is (io/input-stream file)]
        (db/store-rdf is {:format :json-ld, :name (.getName file)})))))



