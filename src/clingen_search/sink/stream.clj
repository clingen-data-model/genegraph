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
            [clojure.string :as s])
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

(def topic-handlers
  {"actionability" :actionability-v1})

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

(defn- poll-once [c]
  (-> c (.poll (Duration/ofMillis 100)) .iterator iterator-seq))

(defn- assign-topic! [consumer topic]
  (let [tp (topic-partitions consumer topic)]
    (.assign consumer tp)
    (if-let [offsets (get @current-offsets topic)]
      (doseq [part tp]
        (if-let [offset (get offsets (.partition part))]
          (.seek consumer part offset)
          (.seekToBeginning consumer [part])))
      (.seekToBeginning consumer tp))))

(defn import-record! [record]
  (try
    (let [iri (-> record .value json/parse-string (get "iri"))
          doc-model (transform-doc {:name iri :format (get topic-handlers (.topic record))}
                                   (.value record))]
      (log/info :fn :import-record! :msg :importing :iri iri)
      (db/load-model doc-model iri))
    (catch Exception e (log/warn :fn :import-record!
                                 :topic (.topic record)
                                 :partition (.partition record)
                                 :offset (.offset record)
                                 :msg (str e)))))

(defn update-offsets! [consumer tps]
  (doseq [tp tps]
    (swap! current-offsets assoc-in [(.topic tp) (.partition tp)] (.position consumer tp)))
  (spit offset-file (pr-str @current-offsets)))

(def run-consumer (atom true))

(defn read-offsets! [] 
  (if (.exists (io/as-file offset-file))
    (reset! current-offsets (-> offset-file slurp edn/read-string))
    (reset! current-offsets {})))

(defn subscribe!
  "Start a Kafka consumer listening to topics in topic-list
  Messages are transformed to RDF, if needed, and imported into triplestore"
  [topic-list]
  (read-offsets!)
  (with-open [consumer (create-kafka-consumer)]
    (doseq [topic topic-list]
      (assign-topic! consumer topic))
    (let [tps (mapcat #(topic-partitions consumer %) topic-list)]
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

(defn topic-data [topic]
  (with-open [c (create-kafka-consumer)]
    (let [tp (topic-partitions c topic)]
      (.assign c tp)
      (.seekToBeginning c tp)
      (loop [records (poll-once c)]
        (let [addl-records (poll-once c)]
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
        (let  [id (re-find #"[A-Za-z0-9-]+$" (get record "iri"))]
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

