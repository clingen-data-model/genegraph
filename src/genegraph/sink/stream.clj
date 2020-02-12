(ns genegraph.sink.stream
  (:require [genegraph.database.load :as db]
            [genegraph.transform.core :refer [transform-doc]]
            [genegraph.env :as env]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [mount.core :refer [defstate]]
            [clojure.edn :as edn]
            [io.pedestal.log :as log]
            [genegraph.transform.actionability]
            [clojure.string :as s]
            [clojure.data :as data]
            [genegraph.database.query :as q]
            [clojure.walk :refer [postwalk]])
  (:import java.util.Properties
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord
            ConsumerRecords]
           [org.apache.kafka.common PartitionInfo TopicPartition]
           [java.time Duration ZonedDateTime ZoneOffset LocalDateTime LocalDate]
           [java.time.format DateTimeFormatter]))

(def config (->> "kafka.edn" io/resource slurp edn/read-string (postwalk #(if (symbol? %) (-> % resolve var-get) %))))

(def offset-file (str env/data-vol "/partition_offsets.edn"))

(def current-offsets (atom {}))

(def end-offsets (atom {}))

(defn document-name [doc-def model]
  (-> (q/select "select ?x where {?x a ?type}"
                {:type (:root-type doc-def)}
                model)
      first
      str))

;; Java Properties object defining configuration of Kafka client
(defn- client-configuration 
  "Create client "
  [broker-config]
  (let [props (new Properties)]
    (doseq [p broker-config]
      (.put props (p 0) (p 1)))
    props))

(defn- create-kafka-consumer
  []
  (let [props (client-configuration)]
    (new KafkaConsumer props)))

(defn- topic-partitions [c topic]
  (let [topic-name (-> config :topics topic :name)
        partition-infos (.partitionsFor c topic-name)]
    (map #(TopicPartition. (.topic %) (.partition %)) partition-infos)))

(defn- poll-once 
  ([c] (-> c (.poll (Duration/ofMillis 100)) .iterator iterator-seq)))

(defn- consumer-for-topic [topic]
  (let [cluster-key (-> config :topics topic :cluster)]
    (-> config
        :clusters
        cluster-key
        client-configuration 
        KafkaConsumer.)))

(defn- read-end-offsets! [consumer topic-partitions]
  (let [kafka-end-offsets (.endOffsets consumer topic-partitions)
        end-offset-map (reduce (fn [acc [k v]]
                                 (assoc acc [(.topic k) (.partition k)] v))
                               {} kafka-end-offsets)]
    (reset! end-offsets end-offset-map)))

(defn import-record! [record doc-def]
  (try
    (let [doc-model (transform-doc (assoc doc-def :document (.value record)))
          iri (document-name doc-def doc-model)]
      (log/info :fn :import-record!
                :msg :importing
                :iri iri
                :topic (.topic record)
                :partition (.partition record)
                :offset (.offset record)
                :validation-model (:validation-model doc-def)
                :time (.format DateTimeFormatter/ISO_DATE_TIME
                               (LocalDateTime/ofEpochSecond (/ (.timestamp record) 1000)
                                                            0
                                                            ZoneOffset/UTC)))
      (db/load-model doc-model iri {:validate (:validation-model doc-def)}))
    (catch Exception e 
      (.printStackTrace e)
      (log/warn :fn :import-record!
                :topic (.topic record)
                :partition (.partition record)
                :offset (.offset record)
                :record record
                :validation-model (:validation-model doc-def)
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



(defn- assign-topic!
  "Return a function that creates a consumer, sets it to listen to all partitions available
  for that topic, assigns the most recently read offsets for those partitions, and keeps them
  up-to-date while they are read. Terminates when the run-consumer atom returns false"
  [topic]
  (fn []
    (let [consumer (consumer-for-topic topic)
          tp (topic-partitions consumer topic)
          topic-name (-> config :topics topic :name)]
      (.assign consumer tp)
      (doseq [part tp]
        (if-let [offset (get @current-offsets [topic-name (.partition part)])]
          (.seek consumer part offset)
          (.seekToBeginning consumer [part])))
      (read-end-offsets! consumer tp)
      (while @run-consumer
        (doseq [record (poll-once consumer)]
          (import-record! record (-> config :topics topic)))
        (update-offsets! consumer tp)))))

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
  (doseq [topic topic-list]
    (let [thread (-> topic assign-topic! Thread.)]
      (log/info :fn :subscribe!
                :msg (str "assigning " topic))
      (.start thread))))

(defstate consumer-thread
  :start (let [topics (map keyword (s/split env/dx-topics #";"))]
           (reset! run-consumer true)
           (subscribe! topics))
  :stop  (reset! run-consumer false))

(defn long-poll [c]
  (-> c (.poll (Duration/ofMillis 2000)) .iterator iterator-seq))

(defn topic-data [topic]
  (with-open [c (consumer-for-topic topic)]
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
        
        (let  [id (or (.key record-payload)
                   (re-find #"[A-Za-z0-9-]+$" (or (str (get record "iri") ) (get-in record ["interpretation" "id"]))))
               idx (.offset record-payload)
               wg (get-in record ["affiliations" 0 "id"])]
              (spit (str folder "/" idx "-" id ".json") (.value record-payload)))))))

(defn load-local-data 
  "Treat all files stored in dir as loadable data in json-ld form, load them
  into base datastore"
  [dir]
  (let [files (filter #(.isFile %) (-> dir io/file file-seq))]
    (doseq [file files]
      (println "importing " (.getName file))
      (with-open [is (io/input-stream file)]
        (db/store-rdf is {:format :json-ld, :name (.getName file)})))))



