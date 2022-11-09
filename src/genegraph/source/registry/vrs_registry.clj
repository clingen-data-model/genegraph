(ns genegraph.source.registry.vrs-registry
  (:require [clojure.core.async :as async]
            [genegraph.annotate :as ann]
            [genegraph.sink.event :as ev]
            [genegraph.sink.stream :as stream]
            [genegraph.source.registry.redis :as redis]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.transform.types :as xform-types]
            [io.pedestal.log :as log]
            [com.climate.claypoole :as cp]
            [mount.core :as mount])
  (:import (java.time Duration)))

(def add-data-interceptor
  {:name ::add-data-interceptor
   :enter (fn [event] (xform-types/add-data event))
   :leave (fn [event] event)})

(def interceptor-chain
  [#_event-recorder/record-event-interceptor
   ann/add-metadata-interceptor
   #_add-data-interceptor
   ;; Right now add-model calls add-data internally for clinvar messages
   ann/add-model-interceptor
   ann/add-iri-interceptor
   #_ann/add-validation-shape-interceptor
   #_ann/add-validation-context-interceptor
   #_ann/add-validation-interceptor
   #_ann/add-subjects-interceptor
   #_ann/add-action-interceptor
   #_ann/add-replaces-interceptor
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
  (let [batches (partition-all 10 event-seq)]
    (doall
     (flatten-one-level
      (cp/pmap pool
               (fn [batch]
                 (mapv (fn [event]
                         (-> event
                             (assoc ::ev/interceptors interceptor-chain)
                             (ev/process-event!)))
                       batch))
               batches)))))

(defn event-processing-fn
  "Used as an arg to genegraph.sink.stream/assign-topic-consumer.
   Takes a seq of clojurified Kafka messages. (consumer-record-to-clj)"
  [pool event-seq]
  (log/info :fn :event-processing-fn :batch-size (count event-seq))
  (dorun (cp/pmap pool
                  (fn [event]
                    (-> event
                        (assoc ::ev/interceptors interceptor-chain)
                        (ev/process-event!)))
                  event-seq)))


(def running-atom (atom true))

(mount/defstate thread-pool
  :start (cp/threadpool 10)
  :stop (cp/shutdown thread-pool))

(def events-with-exceptions (atom []))

(defn -main [& args]
  ;; Don't start the cancervariants rocksdb states. If redis is not configured,
  ;; those calls will fail.
  ;; Starting genegraph.transform.clinvar.cancervariants/redis-db
  ;; will throw an exception if Redis is not configured, or is not connectable.
  (assert (genegraph.transform.clinvar.cancervariants/redis-configured?)
          "Redis must be configured with CACHE_REDIS_URI")
  (assert (redis/connectable? vicc/_redis-opts)
          (str "Could not connect to redis instance " (prn-str vicc/_redis-opts)))
  (mount/start #'genegraph.database.instance/db
               #'genegraph.database.property-store/property-store
               #'genegraph.transform.clinvar.cancervariants/redis-db
               #'genegraph.source.registry.vrs-registry/thread-pool)
  (let [batch-limit Long/MAX_VALUE
        batch-counter (atom 0)]
    (with-open [consumer (stream/assigned-consumer-for-topic :clinvar-raw)]
      (.seekToBeginning consumer (.assignment consumer))
      (while (and @running-atom (<= (swap! batch-counter inc) batch-limit))
        (when-let [batch (.poll consumer (Duration/ofSeconds 5))]
          (dorun
           (->> batch
                (map #(stream/consumer-record-to-clj % :clinvar-raw))
                (event-processing-fn-batched thread-pool)
                (filter #(:exception %))
                (map #(swap! events-with-exceptions conj %)))))))))


;; (get-key vicc/redis-db
;;          ["NC_000003.12:g.37048629_37048631delCTA" :hgvs])

;; (vicc/get-from-cache "NC_000002.12:g.237527482delC" :hgvs)
