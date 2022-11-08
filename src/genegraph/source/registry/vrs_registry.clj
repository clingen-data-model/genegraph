(ns genegraph.source.registry.vrs-registry
  (:require [clojure.core.async :as async]
            [genegraph.annotate :as ann]
            [genegraph.server]
            [genegraph.sink.event :as ev]
            [genegraph.sink.stream :as stream]
            [genegraph.source.registry.redis :as redis]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.transform.types :as xform-types]
            [io.pedestal.log :as log]
            [com.climate.claypoole :as cp]
            [mount.core :as mount])
  (:import (java.time Duration)))

(def keep-unused ['genegraph.server])

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

(defn pmap-pool
  "Variant of pmap that accepts a user-defined ThreadPoolExecutor.
   It is recommended to construct the pool with claypoole/threadpool"
  [pool map-fn & arg-seqs]
  (apply (partial cp/pmap pool map-fn) arg-seqs))

(defn event-processing-fn
  "Used as an arg to genegraph.sink.stream/assign-topic-consumer.
   Takes a seq of clojurified Kafka messages. (consumer-record-to-clj)"
  [pool event-seq]
  (log/info :fn :event-processing-fn :batch-size (count event-seq))
  (dorun (pmap-pool pool
                    (fn [event]
                      (-> event
                          (assoc ::ev/interceptors interceptor-chain)
                          (ev/process-event!)))
                    event-seq)))


(def running-atom (atom true))

(mount/defstate thread-pool
  :start (cp/threadpool 10)
  :stop (cp/shutdown thread-pool))

(defn -main [& args]
  ;; Don't start the cancervariants rocksdb states. If redis is not configured,
  ;; those calls will fail.
  ;; Starting genegraph.transform.clinvar.cancervariants/redis-db
  ;; will throw an exception if Redis is not configured, or is not connectable.
  (assert (genegraph.transform.clinvar.cancervariants/redis-configured?)
          "Redis must be configured with CACHE_REDIS_URI")
  (assert (redis/connectable? vicc/_redis-opts)
          (str "Could not connect to redis instance " (prn-str vicc/_redis-opts)))
  (mount/start #'genegraph.server/server
               #'genegraph.database.instance/db
               #'genegraph.database.property-store/property-store
               #'genegraph.transform.clinvar.cancervariants/redis-db
               #'genegraph.source.registry.vrs-registry/thread-pool)
  (let [batch-limit Long/MAX_VALUE
        batch-counter (atom 0)]
    (with-open [consumer (stream/assigned-consumer-for-topic :clinvar-raw)]
      (.seekToBeginning consumer (.assignment consumer))
      (while (and @running-atom (<= (swap! batch-counter inc) batch-limit))
        (when-let [batch (.poll consumer (Duration/ofSeconds 5))]
          (->> batch
               (map #(stream/consumer-record-to-clj % :clinvar-raw))
               (event-processing-fn thread-pool)))))))


;; (get-key vicc/redis-db
;;          [[:variation-expression "NC_000003.12:g.37048629_37048631delCTA"]
;;           [:variation-type :hgvs]])

;; ;; 12 :42:20.499 [nREPL-session-f6d145e1-75c0-4f1a-bcdc-4373579f7e7c] INFO  g.transform.clinvar.cancervariants  - {:fn :normalize-canonical, :variation-expression "NC_000003.12:g.37048629_37048631delCTA", :line 75}

;; (vicc/get-from-cache "NC_000002.12:g.237527482delC" :hgvs)
