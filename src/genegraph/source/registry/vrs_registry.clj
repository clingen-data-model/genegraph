(ns genegraph.source.registry.vrs-registry
  (:require [clojure.core.async :as async]
            [com.climate.claypoole :as cp]
            [genegraph.annotate :as ann]
            [genegraph.migration :as migration]
            [genegraph.server :as server]
            [genegraph.sink.event :as ev]
            [genegraph.sink.stream :as stream]
            [genegraph.source.registry.redis :as redis]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.transform.clinvar.common :refer [with-retries]]
            [genegraph.transform.types :as xform-types]
            [io.pedestal.log :as log]
            [mount.core :as mount])
  (:import (java.time Duration Instant)
           (org.apache.kafka.common TopicPartition)))

(def add-data-interceptor
  {:name ::add-data-interceptor
   :enter (fn [event] (xform-types/add-data event))
   :leave (fn [event] event)})

(def interceptor-chain
  [ann/add-metadata-interceptor
   #_add-data-interceptor
   ;; Right now add-model calls add-data internally for clinvar messages
   ann/add-model-interceptor
   ann/add-iri-interceptor
   #_event/stream-producer-interceptor])

(defn channel-worker
  "Target function for worker threads, which runs until input-channel is closed (<! returns nil).
   Calls the-fn for every value retrieved from channel."
  [input-channel the-fn]
  (loop [val (async/<!! input-channel)]
    (when val
      (the-fn val)
      (recur (async/<!! input-channel)))))

(defn flatten-one-level [s]
  (for [v s e v] e))

(defn event-processing-fn-batched
  "Same as event-processing-fn, but parallelizes work on
   subseqs of event-seq instead of single elements of event-seq
   in order to increase the amount of work each thread task does."
  [pool event-seq]
  (flatten-one-level
   (doall
    (cp/pmap pool
             (fn [batch]
               (doall
                (mapv (fn [event]
                        (-> event
                            (assoc ::ev/interceptors interceptor-chain)
                            (ev/process-event!)))
                      batch)))
             (partition-all 20 event-seq)))))

(defn event-processing-fn
  "Used as an arg to genegraph.sink.stream/assign-topic-consumer.
   Takes a seq of clojurified Kafka messages. (consumer-record-to-clj)"
  [pool event-seq]
  (log/info :fn :event-processing-fn :batch-size (count event-seq))
  (doall (cp/pmap pool
                  (fn [event]
                    (-> event
                        (assoc ::ev/interceptors interceptor-chain)
                        (ev/process-event!)))
                  event-seq)))


(def running-atom (atom true))

(mount/defstate thread-pool
  :start (cp/threadpool 20)
  :stop (cp/shutdown thread-pool))

(defn reset-consumer-position
  ;; Try to remove this function and replace with a stream.clj function
  "Resets the consumer position either to the stored offset in partition_offsets.edn,
   or to the beginning of the topic if no offset was stored."
  [consumer topic-partition]
  (if-let [offset (get @stream/current-offsets [(.topic topic-partition)
                                                (.partition topic-partition)])]
    (.seek consumer topic-partition offset)
    (.seekToBeginning consumer [topic-partition]))
  consumer)

(defn wait-for-redis-connectability
  "Will wait up to max-ms milliseconds for the redis server specified by
   redis-opts to accept connections."
  [redis-opts max-ms]
  (loop [i 0]
    (when (not (redis/connectable? redis-opts))
      (if (>= (* i 1000) max-ms)
        (throw (ex-info (str "Could not connect to redis " redis-opts)
                        {:redis-opts redis-opts}))
        (do (log/info :fn :wait-for-redis-connectability
                      :msg "Waiting for redis availability"
                      :redis-opts redis-opts
                      :waited-seconds i)
            (Thread/sleep (* 1 1000))
            (recur (inc i))))))
  (log/info :fn :wait-for-redis-connectability
            :msg "Connected"
            :redis-opts redis-opts))

(defn -main [& args]
  ;; Don't start the cancervariants rocksdb states. If redis is not configured,
  ;; those calls will fail.
  ;; Starting genegraph.transform.clinvar.cancervariants/redis-db
  ;; will throw an exception if Redis is not configured or is not connectable.
  (assert (vicc/redis-configured?)
          "Redis must be configured with CACHE_REDIS_URI")
  (mount/start #'genegraph.server/server)
  (wait-for-redis-connectability vicc/redis-opts (* 30 1000))
  (migration/populate-data-vol-if-needed)
  (mount/start #'genegraph.database.instance/db
               #'genegraph.database.property-store/property-store
               #'genegraph.transform.clinvar.cancervariants/cache-db
               #'genegraph.source.registry.vrs-registry/thread-pool)
  (assert (= :redis (:type genegraph.transform.clinvar.cancervariants/cache-db)))
  (log/info :fn ::-main :running-states (mount/running-states))
  (let [batch-limit Long/MAX_VALUE
        batch-counter (atom 0)
        topic-kw :clinvar-raw
        topic-name (-> stream/config :topics topic-kw :name)]
    (stream/initialize-current-offsets!) ; Reads partition_offsets.edn
    (with-retries 60 (* 60 1000) ; 60 1min retries
      (fn []
        (when @running-atom ; Returning nil will short-circuit with-retries
          (log/info :fn ::-main :msg "Opening consumer")
          (with-open [consumer (stream/assigned-consumer-for-topic topic-kw)]
            (doseq [tp (.assignment consumer)]
              (reset-consumer-position consumer tp))
            (while (and @running-atom (<= (swap! batch-counter inc) batch-limit))
              (when-let [batch (with-retries 60 (* 60 1000) ; 60 1min retries
                                 #(let [pb (stream/poll-catch-exception consumer)]
                                    (if (= :error pb)
                                      (throw (ex-info "Exception in polling" {:assignment (.assignment consumer)}))
                                      pb)))]
                (when (not-empty batch)
                  (let [time-start (Instant/now)]
                    (dorun
                     (->> batch
                          (map #(stream/consumer-record-to-event % topic-kw))
                          (event-processing-fn-batched thread-pool)
                          (filter #(:exception %))
                          (map #(log/error :fn ::-main :event-with-exception %))))
                    (let [batch-duration (Duration/between time-start (Instant/now))]
                      (log/info :fn ::-main
                                :batch-start (some-> batch first .offset)
                                :batch-end (some-> batch last .offset)
                                :batch-size (.count batch)
                                :batch-duration (.toString batch-duration)
                                :time-per-event (.toString (.dividedBy batch-duration (long (.count batch))))))))
                (stream/update-consumer-offsets! consumer [(TopicPartition. topic-name 0)])))))))))


(defn count-variant-types
  "Gets keys and values from the redis instance and counts them by the :type field in the value"
  [redis-opts]
  (assert (redis/connectable? redis-opts) (str "not connectable: " redis-opts))
  (letfn [(count-fn [agg v]
            (assoc agg (get v "type") (inc (get agg (get v "type") 0))))
          (flatten1 [things] (for [thing things a thing] a))]
    (let [cache-vals (->> (redis/key-seq redis-opts :scan-count 1000)
                          (partition-all 1000)
                          (map #(redis/get-keys-pipelined redis-opts %))
                          flatten1)]
      (reduce count-fn {} cache-vals))))
