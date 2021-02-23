(ns genegraph.sink.stream
  (:require [genegraph.sink.event :as event]
            [genegraph.annotate :as annotate]
            [genegraph.env :as env]
            [clojure.java.io :as io]
            [mount.core :refer [defstate]]
            [clojure.edn :as edn]
            [io.pedestal.log :as log]
            [clojure.string :as s]
            [clojure.walk :refer [postwalk]]
            [taoensso.nippy :as nippy]
            [genegraph.rocksdb :as rocksdb])
  (:import java.util.Properties
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord
            ConsumerRecords]
           [org.apache.kafka.common PartitionInfo TopicPartition]
           [java.time Duration ZonedDateTime ZoneOffset LocalDateTime LocalDate]
           [java.time.format DateTimeFormatter]))

(def config (->> "kafka.edn" io/resource slurp edn/read-string (postwalk #(if (symbol? %) (-> % resolve var-get) %))))

(defn offset-file []
  (str env/data-vol "/partition_offsets.edn"))

(def current-offsets (atom {}))

(def end-offsets (atom {}))

(def topic-state (atom {}))

(defn consumer-record-to-clj [consumer-record spec]
  {::annotate/format spec 
   ::event/key (.key consumer-record)
   ::event/value (.value consumer-record)
   ::timestamp (.timestamp consumer-record)
   ::topic (.topic consumer-record)
   ::partition (.partition consumer-record)
   ::offset (.offset consumer-record)})

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
  ([c] (-> c (.poll (Duration/ofMillis 500)) .iterator iterator-seq)))

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
    (swap! end-offsets merge end-offset-map)))

(defn update-offsets! [consumer tps]
  (doseq [tp tps]
    (swap! current-offsets assoc [(.topic tp) (.partition tp)] (.position consumer tp)))
  (spit (offset-file) (pr-str @current-offsets)))

(def run-consumer (atom true))

(defn read-offsets! [] 
  (if (.exists (io/as-file (offset-file)))
    (reset! current-offsets (-> (offset-file) slurp edn/read-string))
    (reset! current-offsets {})))

(defn- assign-topic!
  "Return a function that creates a consumer, sets it to listen to all partitions available
  for that topic, assigns the most recently read offsets for those partitions, and keeps them
  up-to-date while they are read. Terminates when the run-consumer atom returns false"
  [topic]
  (fn []
    (with-open [consumer (consumer-for-topic topic)]
      (let [tp (topic-partitions consumer topic)
            topic-name (-> config :topics topic :name)]
        (.assign consumer tp)
        (doseq [part tp]
          (if-let [offset (get @current-offsets [topic-name (.partition part)])]
            (.seek consumer part offset)
            (.seekToBeginning consumer [part])))
        (read-end-offsets! consumer tp)
        (while @run-consumer
          (let [records (poll-once consumer)]
            (->> records
                 (map #(consumer-record-to-clj % topic))
                 event/process-event-seq!)
            (update-offsets! consumer tp)))
        (swap! topic-state assoc topic :stopped)))))

(defn up-to-date? 
  "Returns true if all partitions of all topics subscribed to have had messages
  consumed up to the latest offset when the consumer was started."
  []
  (log/debug :fn :up-to-date? :current-offsets @current-offsets :end-offsets @end-offsets)
  (when (= (set (keys @current-offsets)) (set (keys @end-offsets)))
    (let [partition-is-up-to-date? (merge-with <= @end-offsets @current-offsets)]
      (if (some false? (vals partition-is-up-to-date?))
        false
        true))))

(defn consumers-closed?  []
  (if (some #(= :running %) (vals @topic-state))
    false
    true))

(defn subscribe!
  "Start a Kafka consumer listening to topics in topic-list
  Messages are transformed to RDF, if needed, and imported into triplestore"
  [topic-list]
  (read-offsets!)
  (doseq [topic topic-list]
    (let [thread (-> topic assign-topic! Thread.)]
      (log/info :fn :subscribe!
                :msg (str "assigning " topic))
      (.start thread)
      (swap! topic-state assoc topic :running))))

(defstate consumer-thread
  :start (let [topics (map keyword (s/split env/dx-topics #";"))]
           (reset! run-consumer true)
           (subscribe! topics))
  :stop  (reset! run-consumer false))

(defn long-poll [c]
  (-> c (.poll (Duration/ofMillis 2000)) .iterator iterator-seq))

(defn topic-data
  "Read topic data to disk. Assumes single partition topic."
  ([topic]
   (topic-data topic nil))
  ([topic max-records]
   (with-open [c (consumer-for-topic topic)]
     (let [tps (topic-partitions c topic)
           _ (.assign c tps)
           _ (.seekToBeginning c tps)
           end (if max-records
                 (+ max-records (.position c (first tps)))
                 (-> (.endOffsets c tps) first val))]
       (->> (loop [records (poll-once c)]
              (println (.position c (first tps)) "/" end)
              (if (< (.position c (first tps)) end)
                (recur (concat records (poll-once c)))
                records))
            (mapv #(consumer-record-to-clj % topic)))))))

(defn topic-data-to-disk 
  "Read topic data to disk. Assumes single partition topic."
  [topic dest]
  (with-open [c (consumer-for-topic topic)]
    (let [tps (topic-partitions c topic)
          end (-> (.endOffsets c tps) first val)]
      (.assign c tps)
      (.seekToBeginning c tps)
      (loop [records (poll-once c)]
        (doseq [r records]
          (with-open [w (io/output-stream (str dest "/" (name topic) "-" (.offset r)))]
            (.write w (nippy/freeze (consumer-record-to-clj r topic)))))
        (when (< (.position c (first tps)) end)
          (recur (poll-once c)))))))


(defn topic-data-to-rocksdb
  "Read topic data to disk. Assumes single partition topic. Optiionally accepts key-fn that accepts the
  value off the topic and returns a sequence to use as key. In the event that key-fn returns nil or is not supplied, 
  will use offset as key."
  ([topic db-name]
   (topic-data-to-rocksdb topic db-name (constantly nil)))
  ([topic db-name key-fn]
   (with-open [c (consumer-for-topic topic)
               db (rocksdb/open db-name)]
     (let [tps (topic-partitions c topic)
           end (-> (.endOffsets c tps) first val)]
       (.assign c tps)
       (.seekToBeginning c tps)
       (loop [records (poll-once c)]
         (println (.position c (first tps)) "/" end)
         (doseq [record records]
           (let [annotated-record (consumer-record-to-clj record topic)
                 k (or (key-fn annotated-record) (::offset annotated-record))]
             (rocksdb/rocks-put-multipart-key! db k annotated-record)))
         (when (< (.position c (first tps)) end)
           (recur (poll-once c))))))))




