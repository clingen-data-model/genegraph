(ns genegraph.sink.stream
  (:require [genegraph.annotate :as annotate]
            [genegraph.env :as env]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [io.pedestal.log :as log]
            [clojure.string :as str]
            [clojure.walk :refer [postwalk]]
            [taoensso.nippy :as nippy]
            [genegraph.rocksdb :as rocksdb])
  (:import java.util.Properties
           java.nio.ByteBuffer
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord ConsumerRecords]
           [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.common PartitionInfo TopicPartition]
           [java.time Duration ZonedDateTime ZoneOffset LocalDateTime LocalDate Instant]
           [java.time.format DateTimeFormatter]))

(def config (->> "kafka.edn"
                 io/resource slurp
                 edn/read-string
                 (postwalk #(if (symbol? %) (-> % resolve var-get) %))))

(defn consumer-topics []
  (mapv keyword (str/split env/dx-topics #";")))
      
(defn offset-file []
  (str env/data-vol "/partition_offsets.edn"))

;; This atom file may contain some, none, or all of the consumer topics defined 
;; for processing in the environment. All offsets that have been previously 
;; recorded in partition_offsets.edn are be preserved, and any new consumer topics
;; defined for processing will have their offset state tracked.
;; Map is in the form { [topic-name partition] current-offset}
(defonce current-offsets (atom {}))

;; This atom contains the end offsets for the current topics defined in the environment.
;; Map is in the form { [topic-name partition] end-offset}
(defonce end-offsets (atom {}))

;; This promise is only delivered once, the initial time that the offsets of the current
;; topics defined in the environment are completely brought up to their end offsets.
(defonce consumer-offsets-up-to-date (promise))

;; Consumer topic state is either :running or :stopped
;; Map is in the form { topic state }
(defonce consumer-topic-state (atom {}))
(defonce consumer-topics-closed (promise))

;; In transformer mode, we store a single producer per cluster
;; Map is in the form { cluster producer }.
;; See the :clusters entry in kafka.edn
(defonce producers (atom {}))


;; Atom containing information about declared consumers,
(defonce consumers (atom {}))

(defn consumer-record-to-event
  "Imparts genegraph event semantics to consumer-record where topic-key
  represents the key of the consumed topic found in kafka.edn"
  [consumer-record topic-key]
  {::annotate/format topic-key
   :genegraph.sink.event/key (.key consumer-record)
   :genegraph.sink.event/value (.value consumer-record)
   ::timestamp (.timestamp consumer-record)
   ::topic (.topic consumer-record)
   ::partition (.partition consumer-record)
   ::offset (.offset consumer-record)
   ::annotate/producer-topic (-> config
                                 :topics
                                 topic-key
                                 :producer-topic)})

;; Java Properties object defining configuration of Kafka client
(defn- client-configuration 
  "Create client "
  [broker-config]
  (let [props (new Properties)]
    (doseq [p broker-config]
      (.put props (p 0) (p 1)))
    props))

(defn- topic-partitions
  [consumer topic]
  (let [topic-name (-> config :topics topic :name)
        partition-infos (.partitionsFor consumer topic-name)]
    (map #(TopicPartition. (.topic %) (.partition %)) partition-infos)))

(defn- poll-catch-exception
  "Performs periodic polling on consumer, returns a seq of consumed
  messages or :error on poll exception"
  [consumer]
  (try
    (-> consumer
        (.poll (Duration/ofMillis 500))
        .iterator
        iterator-seq)
    (catch Exception e
      (log/error :fn :poll-catch-exception
                :exception e
                :msg "Polling exception caught."
                :partitions (.assignment consumer))
      :error)))

(defn- poll-once
  "Attempts to poll consumer one time and return polled messages. Upon exception, retries
  polling over an increasing series of sleep times. Throws an exception if poll attempts are
  unsuccessful."
  [consumer]
  (loop [sleep-times-ms [1000 3000 5000 7000 9000]]
    (let [sleep-ms (first sleep-times-ms)
          poll-result (poll-catch-exception consumer)]
      (if-not (= :error poll-result)
        poll-result
        (do
          (when (nil? sleep-ms)
            (log/error :fn :poll-once
                         :msg "Polling exceeds max retries."
                         :partitions (.assignment consumer))
            (throw (Exception. "Signal exception - polling at max retries")))
          (Thread/sleep sleep-ms)
          (recur (rest sleep-times-ms)))))))
            
(defn topic-cluster-key [topic]
  (-> config :topics topic :cluster))

(defn topic-cluster-config [topic]
   (let [cluster-key (topic-cluster-key topic)]
    (-> config
        :clusters
        cluster-key
        client-configuration)))

(defn- consumer-for-topic
  "Return consumer for topic"
  [topic]
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

(defn consumer-topic-names []
  (->> (consumer-topics) (select-keys (:topics config)) vals (map :name) set))

(defn filter-by-consumer-topic-names
  "Filter any map collection that is keyed by [topic partition]
   by the current list of topics defined in the environment."
  [offset-coll]
  (reduce (fn [acc [[topic-name partition] offset]]
            (if (contains? (consumer-topic-names) topic-name)
              (assoc acc [topic-name partition] offset)
              acc))
          {}
          offset-coll))

(defn offsets-up-to-date-status []
(if (= (-> (filter-by-consumer-topic-names @current-offsets) keys set) (-> @end-offsets keys set))
    (merge-with <= @end-offsets (filter-by-consumer-topic-names @current-offsets))
    {}))

(defn end-offsets-has-all-consumer-topics?
  "This is a gate to ensure that all topic for processing in the environment
   have their end offsets recorded"
  []
  (= (->> @end-offsets keys (map first) count) (count (set (consumer-topics)))))

(defn consumers-up-to-date?
  "Checks if the consumers for the list of topics defined for processing
   in the environment have processed up to their topic partitions end offsets"
  []
  (let [offsets-status (offsets-up-to-date-status)]
    (and (end-offsets-has-all-consumer-topics?)
         (some? (keys offsets-status))
         (every? true? (vals (filter-by-consumer-topic-names offsets-status))))))

(defn check-consumers-up-to-date-status!
  "Returns true if all partitions of all topics subscribed to have had messages
  consumed up to the latest offset when the consumer was started."
  []
  (when (consumers-up-to-date?)
    (deliver consumer-offsets-up-to-date true))
  (log/info :fn :check-consumers-up-to-date-status?
            :current-offsets @current-offsets
            :end-offsets @end-offsets
            :offsets-up-to-date (offsets-up-to-date-status)))

(defn initialize-current-offsets! []
  (if (.exists (io/as-file (offset-file)))
    (reset! current-offsets (-> (offset-file) slurp edn/read-string))
    (reset! current-offsets {})))

(defn update-consumer-offsets! [consumer topic-partitions]
  (read-end-offsets! consumer topic-partitions)
  (doseq [tp topic-partitions]
    (let [topic-name (.topic tp)
          partition (.partition tp)
          key [topic-name partition]]
      (when (not= (get @current-offsets key) (get @end-offsets key))
        (swap! current-offsets assoc key (.position consumer tp))
        (spit (offset-file) (pr-str @current-offsets)))))
  (when-not (realized? consumer-offsets-up-to-date)
    (check-consumers-up-to-date-status!)))

(defonce run-consumer (atom true))

(defn consumers-closed?  []
  (every? #(= :stopped %) (vals @consumer-topic-state)))

(defn wait-for-topics-closed []
  @consumer-topics-closed)

(defn metrics->clj [metrics-map]
  (into {} (map (fn [metric]
                  [(-> metric .metricName .name keyword)
                   (-> metric .metricValue)])
                (vals metrics-map))))

(defn consumers-are-polling? []
  (let [max-drift (* 1000 60 5)
        latest-acceptable-poll (- (System/currentTimeMillis) max-drift)]
    (->> @consumers vals (map :last-polled) (some #(< latest-acceptable-poll %)))))

(defn topic-name
  "Return the kafka topic name from the topic keyword as found in kafka.edn."
  [topic-key]
  (-> config :topics topic-key :name))

(defn- assign-topic-partitions-to-consumer!
  "Assigns the topic partitions to a topic consumer."
  [consumer topic topic-partitions]
  (swap! consumers assoc topic {:kafka-consumer consumer})
  (.assign consumer topic-partitions))

(defn- initialize-topic-consumer-offset!
  "Sets the consumer offset to the last saved offset for the topic/partition pairs
   or to the beginning when no offset has been saved. Records the offsets."  
  [consumer topic partitions]
  (doseq [partition partitions]
    (if-let [offset (get @current-offsets [(topic-name topic) (.partition partition)])]
      (.seek consumer partition offset)
      (.seekToBeginning consumer [partition])))
  (update-consumer-offsets! consumer partitions))

(defn- poll-topic-consumer!
  "When run-consumer atom is true, polls consumer for messages, converts messages recieved to genegraph events,
  processes the events through the event-processing-fn function, and records the last time the consumer
  was polled. Records the offsets."
  [event-processing-fn consumer topic topic-partitions]
  (while @run-consumer
    (swap! consumers assoc-in [topic :last-polled] (System/currentTimeMillis))
    (when-let [records (poll-once consumer)]
      (->> records
           (map #(consumer-record-to-event % topic))
           event-processing-fn))
    (update-consumer-offsets! consumer topic-partitions)))

(defn- setup-topic-consumer!
  "Perform setup tasks on consumer which includes assigning the topic topic-partitions to
  the consumer and seeking to the current recorded offset for each."
  [consumer topic topic-partitions]
  (assign-topic-partitions-to-consumer! consumer topic topic-partitions)
  (initialize-topic-consumer-offset! consumer topic topic-partitions))

(defn- mark-topic-consumer-stopped!
  "Log the state of the topic as closed and deliver promise
  when all of the consumers are closed."
  [topic]
  (swap! consumer-topic-state assoc topic :stopped)
  (log/debug :fn :get-topic-consumer!
             :consumer-topic-state @consumer-topic-state
             :consumers-closed? (consumers-closed?))
  (when (consumers-closed?)
    (deliver consumer-topics-closed true)))

(defn- process-topic-consumer!
  "Establish a topic consumer for topic and its partitions, and process polled messages
  through the event-processing-fn while run-consumer atom is true. Shuts down the consumer
  when polling has completed."
  [event-processing-fn topic]
  (with-open [consumer (consumer-for-topic topic)]
    (let [topic-partitions (topic-partitions consumer topic)]
      (setup-topic-consumer! consumer topic topic-partitions)
      (poll-topic-consumer! event-processing-fn consumer topic topic-partitions)
      (mark-topic-consumer-stopped! topic))))

(defn- assign-topic-consumer!
  "Returns a fn that, while the run-consumer atom is true, will attempt to open
  a consumer for topic and process genegraph event sequences through event-processing-fn.
  Exceptions cause reopen retries over a repeating series of sleep intervals."
  [event-processing-fn topic]
  (fn []
    (while @run-consumer
      (loop [sleep-times-ms [5000 10000 30000 60000 120000]]
        (let [sleep-ms (first sleep-times-ms)]
          (when (and sleep-ms @run-consumer)
            (try
              (process-topic-consumer! event-processing-fn topic)
              (catch Exception e
                (log/error :fn :assign-topic-consumer!
                           :exception e
                           :msg (str "Failure assigning topic consumer. Sleeping " sleep-ms "ms before retrying.")
                           :topic topic)))
            (Thread/sleep sleep-ms)
            (recur (rest sleep-times-ms))))))))

(defn wait-for-topics-up-to-date []
  @consumer-offsets-up-to-date)

(defn subscribe-consumers!
  "Start a Kafka consumer listening to topics in topic-list
  Topic-list is the list of topic keywords
  Messages are transformed to RDF, if needed, and imported into triplestore"
  [event-processing-fn topic-list]
  (initialize-current-offsets!)
  (doseq [topic topic-list]
    (let [thread (Thread. (assign-topic-consumer! event-processing-fn topic))]
      (log/info :fn :subscribe-consumers!
                :msg (str "assigning consumer " topic))
      (.start thread)
      (swap! consumer-topic-state assoc topic :running))))

(defn run-consumers!
  ([event-processing-fn]
   (subscribe-consumers! event-processing-fn (consumer-topics)))
  ([event-processing-fn consumer-topics]
   (subscribe-consumers! event-processing-fn consumer-topics)))

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
            (mapv #(consumer-record-to-event % topic)))))))

(defn topic-data-to-output
  "Read topic data to disk. Assumes single partition topic.
  fn is a single argument function accepting a stream record,
  and handles the output formatting and file naming of the topic data."
  [topic fn]
  (with-open [c (consumer-for-topic topic)]
    (let [tps (topic-partitions c topic)
          end (-> (.endOffsets c tps) first val)]
      (.assign c tps)
      (.seekToBeginning c tps)
      (loop [records (poll-once c)]
        (doseq [record records]
          (fn record))
        (when (< (.position c (first tps)) end)
          (recur (poll-once c)))))))

(defn topic-data-to-rocksdb
  "Read topic data to disk. Assumes single partition topic. Optiionally accepts key-fn that accepts the
  value off the topic and returns a sequence to use as key. In the event that key-fn returns nil or is not supplied, 
  will use offset as key."
  ([topic db-name]
   (topic-data-to-rocksdb topic db-name {:key-fn (constantly nil)}))
  ([topic db-name options]
   (with-open [c (consumer-for-topic topic)
               db (rocksdb/open db-name)]
     (let [tps (topic-partitions c topic)
           end (or (:end options)
                   (-> (.endOffsets c tps) first val))
           key-fn (:key-fn options)]
       (.assign c tps)
       (.seekToBeginning c tps)
       (loop [records (poll-once c)]
         (println (.position c (first tps)) "/" end)
         (doseq [record records]
           (let [annotated-record (consumer-record-to-event record topic)
                 k (or (key-fn annotated-record) (::offset annotated-record))]
             (rocksdb/rocks-put-raw-key! db k annotated-record)))
         (when (< (.position c (first tps)) end)
           (recur (poll-once c))))))))

(defn store-stream
  ([topic db-name]
   (store-stream topic db-name {}))
  ([topic db-name options]
   (with-open [c (consumer-for-topic topic)
               db (rocksdb/open db-name)]
     (let [tps (topic-partitions c topic)
           end (or (:end options)
                   (-> (.endOffsets c tps) first val))]
       (.assign c tps)
       (.seekToBeginning c tps)
       (println "max offset " (-> (.endOffsets c tps) first val))
       (loop [records (poll-once c)]
         (println (.position c (first tps)) "/" end)
         (doseq [record records]
           (let [annotated-record (consumer-record-to-event record topic)
                 k (.array
                    (doto (ByteBuffer/allocate Long/BYTES)
                      (.putLong (::offset annotated-record))))] 
             (rocksdb/rocks-put-raw-key! db k annotated-record)))
         (when (< (.position c (first tps)) end)
           (recur (poll-once c))))))))
