(ns genegraph.source.registry.vrs-registry
  (:require [clojure.core.async :as async]
            [com.climate.claypoole :as cp]
            [genegraph.annotate :as ann]
            [genegraph.migration :as migration]
            [genegraph.sink.event :as ev]
            [genegraph.server :as server]
            [genegraph.sink.stream :as stream]
            [genegraph.source.registry.redis :as redis]
            [genegraph.transform.clinvar.cancervariants :as vicc]
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

(def events-with-exceptions (atom []))

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

(defn -main [& args]
  ;; Don't start the cancervariants rocksdb states. If redis is not configured,
  ;; those calls will fail.
  ;; Starting genegraph.transform.clinvar.cancervariants/redis-db
  ;; will throw an exception if Redis is not configured, or is not connectable.
  (assert (vicc/redis-configured?)
          "Redis must be configured with CACHE_REDIS_URI")
  (mount/start #'genegraph.server/server)
  ;; TODO add a max like 5 min with an error message
  ;; TODO add a graceful rollout for the redis pod so node cycling doesn't crash connections
  ;; TODO add catch of connection refused on further get/put to the redis
  (while (not (redis/connectable? vicc/redis-opts))
    (log/warn :fn ::-main
              :msg "Could not connect to redis instance"
              :opts vicc/redis-opts)
    (Thread/sleep (* 5 1000)))
  (migration/populate-data-vol-if-needed)
  (mount/start #'genegraph.database.instance/db
               #'genegraph.database.property-store/property-store
               #'genegraph.transform.clinvar.cancervariants/redis-db
               #'genegraph.source.registry.vrs-registry/thread-pool)
  (log/info :fn ::-main :running-states (mount/running-states))
  (let [batch-limit Long/MAX_VALUE
        batch-counter (atom 0)
        topic-kw :clinvar-raw
        topic-name (-> stream/config :topics topic-kw :name)]
    (stream/initialize-current-offsets!) ; Reads partition_offsets.edn
    (with-open [consumer (stream/assigned-consumer-for-topic topic-kw)]
      (doseq [tp (.assignment consumer)]
        (reset-consumer-position consumer tp))
      (while (and @running-atom (<= (swap! batch-counter inc) batch-limit))
        (when-let [batch (.poll consumer (Duration/ofSeconds 5))]
          (when (not-empty batch)
            (let [time-start (Instant/now)]
              (dorun
               (->> batch
                    (map #(stream/consumer-record-to-event % topic-kw))
                    (event-processing-fn-batched thread-pool)
                    (filter #(:exception %))
                    (map #(swap! events-with-exceptions conj %))))
              (let [batch-duration (Duration/between time-start (Instant/now))]
                (log/info :fn ::-main
                          :batch-start (some-> batch first .offset)
                          :batch-end (some-> batch last .offset)
                          :batch-size (.count batch)
                          :batch-duration (.toString batch-duration)
                          :time-per-event (.toString (.dividedBy batch-duration (long (.count batch))))))))
          (stream/update-consumer-offsets! consumer [(TopicPartition. topic-name 0)]))))))
