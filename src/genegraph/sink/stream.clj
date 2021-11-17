(ns genegraph.sink.stream
  (:require [genegraph.annotate :as annotate]
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
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord ConsumerRecords]
           [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.common PartitionInfo TopicPartition]
           [java.time Duration ZonedDateTime ZoneOffset LocalDateTime LocalDate]
           [java.time.format DateTimeFormatter]))

(def config (->> "kafka.edn" io/resource slurp edn/read-string (postwalk #(if (symbol? %) (-> % resolve var-get) %))))

(defn consumer-topics []
  (mapv keyword (s/split env/dx-topics #";")))
      
(defn offset-file []
  (str env/data-vol "/partition_offsets.edn"))

(def current-offsets (atom {}))
(def end-offsets (atom {}))
(def offsets-up-to-date (atom {}))
(def consumer-offsets-up-to-date (promise))
(def consumer-topic-state (atom {}))
(def consumer-topics-closed (promise))
(def producers (atom {}))

(defn consumer-record-to-clj [consumer-record spec]
  {::annotate/format spec 
   :genegraph.sink.event/key (.key consumer-record)
   :genegraph.sink.event/value (.value consumer-record)
   ::timestamp (.timestamp consumer-record)
   ::topic (.topic consumer-record)
   ::partition (.partition consumer-record)
   ::offset (.offset consumer-record)
   ::annotate/producer-topic (-> (:topics config)
                                 spec
                                 :producer-topic)})

;; Java Properties object defining configuration of Kafka client
(defn- client-configuration 
  "Create client "
  [broker-config]
  (let [props (new Properties)]
    (doseq [p broker-config]
      (.put props (p 0) (p 1)))
    props))

(defn- topic-partitions [c topic]
  (let [topic-name (-> config :topics topic :name)
        partition-infos (.partitionsFor c topic-name)]
    (map #(TopicPartition. (.topic %) (.partition %)) partition-infos)))

(defn- poll-once 
  ([c] (-> c (.poll (Duration/ofMillis 500)) .iterator iterator-seq)))

(defn topic-cluster-key [topic]
  (-> config :topics topic :cluster))

(defn topic-cluster-config [topic]
   (let [cluster-key (topic-cluster-key topic)]
    (-> config
        :clusters
        cluster-key
        client-configuration)))

(defn- consumer-for-topic [topic]
  (let [cluster-config (topic-cluster-config topic)
        consumer-config (merge-with into (:common cluster-config) (:consumer cluster-config))] 
    (KafkaConsumer. consumer-config)))

(defn producer-for-topic!
  "Returns a single KafkaProducer per cluster, as KafkaProducers are thread-safe
  and a single producer across threads will generally be faster than
  having multiple instances"
  [topic]
  (let [cluster-key (topic-cluster-key topic)
        cluster-config (topic-cluster-config topic)
        producer (get @producers cluster-key)]
    (if producer
      producer
      (let [producer (-> (merge-with into (:common cluster-config) (:producer cluster-config))
                         client-configuration
                         KafkaProducer.)]
        (swap! producers assoc cluster-key producer)
        producer))))

(defn producer-record-for [kafka-topic-name key value]
   (ProducerRecord. kafka-topic-name key value))

(defn- read-end-offsets! [consumer topic-partitions]
  (let [kafka-end-offsets (.endOffsets consumer topic-partitions)
        end-offset-map (reduce (fn [acc [k v]]
                                 (assoc acc [(.topic k) (.partition k)] v))
                               {} kafka-end-offsets)]
    (swap! end-offsets merge end-offset-map)))

(defn consumers-up-to-date? []
  (and (some? (keys @offsets-up-to-date))
       (= (count (keys @offsets-up-to-date)) (count (consumer-topics)))
       (every? true? (vals @offsets-up-to-date))))

(defn set-up-to-date-status!
  "Returns true if all partitions of all topics subscribed to have had messages
  consumed up to the latest offset when the consumer was started."
  []
  (when (= (set (keys @current-offsets)) (set (keys @end-offsets)))
    (let [partitions-up-to-date? (merge-with <= @end-offsets @current-offsets)]
      (reset! offsets-up-to-date partitions-up-to-date?)
      (when (consumers-up-to-date?)
        (deliver consumer-offsets-up-to-date true))))
  (log/info :fn :set-up-to-date-status?
             :current-offsets @current-offsets
             :end-offsets @end-offsets
             :offsets-up-to-date @offsets-up-to-date))

(defn update-consumer-offsets! [consumer tps]
  (read-end-offsets! consumer tps)
  (doseq [tp tps]
    (let [key [(.topic tp) (.partition tp)]]
      (when (not= (get @current-offsets key) (get @end-offsets key))
        (swap! current-offsets assoc [(.topic tp) (.partition tp)] (.position consumer tp))
        (spit (offset-file) (pr-str @current-offsets)))))
  (when-not (consumers-up-to-date?)
    (set-up-to-date-status!)))

(def run-consumer (atom true))

(defn read-offsets! [] 
  (if (.exists (io/as-file (offset-file)))
    (reset! current-offsets (-> (offset-file) slurp edn/read-string))
    (reset! current-offsets {})))

(defn consumers-closed?  []
  (every? #(= :stopped %) (vals @consumer-topic-state)))

(defn wait-for-topics-closed []
  @consumer-topics-closed)

(defn- assign-topic-consumer!
  "Return a function that creates a consumer, sets it to listen to all partitions available
  for that topic, assigns the most recently read offsets for those partitions, and keeps them
  up-to-date while they are read. Terminates when the run-consumer atom returns false"
  [event-processing-fn topic]
  (fn []
    (with-open [consumer (consumer-for-topic topic)]
      (let [tp (topic-partitions consumer topic)
            topic-name (-> config :topics topic :name)]
        (.assign consumer tp)
        (doseq [part tp]
          (if-let [offset (get @current-offsets [topic-name (.partition part)])]
            (.seek consumer part offset)
            (.seekToBeginning consumer [part])))
        (update-consumer-offsets! consumer tp)
        (while @run-consumer
          (when-let [records (poll-once consumer)]
            (->> records
                 (map #(consumer-record-to-clj % topic))
                 event-processing-fn))
          (update-consumer-offsets! consumer tp))
        (swap! consumer-topic-state assoc topic :stopped)
        (log/debug :fn :assign-topic-consumer!
                   :consumer-topic-state @consumer-topic-state
                   :consumers-closed? (consumers-closed?))
        (when (consumers-closed?)
          (deliver consumer-topics-closed true))))))

(defn wait-for-topics-up-to-date []
  @consumer-offsets-up-to-date)

(defn subscribe-consumers!
  "Start a Kafka consumer listening to topics in topic-list
  Messages are transformed to RDF, if needed, and imported into triplestore"
  [event-processing-fn topic-list]
  (read-offsets!)
  (doseq [topic topic-list]
    (let [thread (Thread. (assign-topic-consumer! event-processing-fn topic))]
      (log/info :fn :subscribe!
                :msg (str "assigning consumer " topic))
      (.start thread)
      (swap! consumer-topic-state assoc topic :running))))

(defn run-consumers! [event-processing-fn]
  (subscribe-consumers! event-processing-fn (consumer-topics)))
  
(defn start-consumers! []
  (reset! run-consumer true))

(defn stop-consumers! []
  (reset! run-consumer false))

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

