(ns clingen-search.sink.stream
  (:require [clingen-search.database.load :as db]
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import java.util.Properties
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord
            ConsumerRecords]
           [org.apache.kafka.common PartitionInfo TopicPartition]
           java.time.Duration))


(def client-properties
  {"bootstrap.servers" (System/getenv "DATA_EXCHANGE_HOST")
   "group.id" (System/getenv "SERVEUR_GROUP")
   "enable.auto.commit" "true"
   "auto.commit.interval.ms" "1000"
   "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
   "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
   "security.protocol" "SSL"
   "ssl.truststore.location" "keys/serveur.truststore.jks"
   "ssl.truststore.password" (System/getenv "SERVEUR_KEY_PASS")
   "ssl.keystore.location" (System/getenv "SERVEUR_KEYSTORE")
   "ssl.keystore.password" (System/getenv "SERVEUR_KEY_PASS")
   "ssl.key.password" (System/getenv "SERVEUR_KEY_PASS")})

;; Java Properties object defining configuration of Kafka client
(defn client-configuration 
  "Create client "
  []
  (let [props (new Properties)]
    (doseq [p client-properties]
      (.put props (p 0) (p 1)))
    props))

(defn consumer
  []
  (let [props (client-configuration)]
    (new KafkaConsumer props)))

(defn topic-partitions [c topic]
  (let [partition-infos (.partitionsFor c topic)]
    (map #(TopicPartition. (.topic %) (.partition %)) partition-infos)))

(defn- poll-once [c]
  (-> c (.poll (Duration/ofSeconds 2)) .iterator iterator-seq))

(defn topic-data [topic]
  (with-open [c (consumer)]
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

(defn load-local-data 
  "Treat all files stored in dir as loadable data in json-ld form, load them
  into base datastore"
  [dir]
  (let [files (filter #(.isFile %) (-> dir io/file file-seq))]
    (doseq [file files]
      (println "importing " (.getName file))
      (with-open [is (io/input-stream file)]
        (db/store-rdf is {:format :json-ld, :name (.getName file)})))))
