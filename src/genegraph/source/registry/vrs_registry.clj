(ns genegraph.source.registry.vrs-registry
  (:require [clj-http.client :as http-client]
            [clojure.core.async :as async]
            [com.climate.claypoole :as cp]
            [genegraph.annotate :as ann]
            [genegraph.migration :as migration]
            [genegraph.server :as server]
            [genegraph.sink.event :as ev]
            [genegraph.sink.stream :as stream]
            [genegraph.source.registry.redis :as redis]
            [genegraph.source.registry.rocks-registry :as rocks-registry]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.transform.clinvar.common :refer [with-retries]]
            [genegraph.transform.types :as xform-types]
            [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [ring.util.request]
            [libpython-clj2.require :refer [require-python]]
            [libpython-clj2.python :refer [py. py.. py.-] :as py]
            [cheshire.core :as json])
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


(defonce keep-running (atom true))

(mount/defstate thread-pool
  :start (cp/threadpool (or (some-> (System/getenv "GENEGRAPH_VRS_CACHE_POOL_SIZE") parse-long)
                            20))
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

(defn poll-with-retries
  "Tries to poll consumer. If an exception occurs, retries."
  [consumer retry-count retry-interval]
  (with-retries retry-count retry-interval ; 60 1min retries
    #(let [batch (stream/poll-catch-exception consumer)]
       (if (= :error batch)
         (throw (ex-info "Exception in polling" {:assignment (.assignment consumer)}))
         batch))))

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

(defn start-states! []
  #_(assert (vicc/redis-configured?)
            "Redis must be configured with CACHE_REDIS_URI")
  #_(wait-for-redis-connectability vicc/redis-opts (* 30 1000))
  (mount/start #'genegraph.server/server)
  (assert (rocks-registry/rocks-http-configured?)
          "RocksDB http must be configured with ROCKSDB_HTTP_URI")
  (rocks-registry/add-routes rocks-registry/routes)
  (mount/start #'rocks-registry/db)


  (reset! rocks-registry/service-map (-> @rocks-registry/service-map
                                         http/default-interceptors
                                         (rocks-registry/remove-interceptor ::http/log-request)))
  (mount/start #'rocks-registry/server)
  (assert (rocks-registry/rocks-http-connectable?)
          (str "RocksDB http was not connectable: " rocks-registry/rocksdb-http-uri))

  (migration/populate-data-vol-if-needed)
  (mount/start #'genegraph.database.instance/db
               #'genegraph.database.property-store/property-store
               #'genegraph.transform.clinvar.cancervariants/cache-db
               #'genegraph.source.registry.vrs-registry/thread-pool))

(defonce consumer-state (atom {:thread nil
                               :watcher nil
                               :keep-running? true
                               :restart-count 0}))

(defn thread-watcher
  "Starts thread-fn in a child thread. Restarts it if it dies.
   Updates state-atom with info that might be useful to caller.
   Setting :keep-running? in the state atom to false signals to this
   watcher that the next time the thread dies, to not restart it.
   :keep-running? false does not mean the watcher should kill the thread.
   Exceptions in child thread are caught and logged."
  [thread-fn state-atom]
  (swap! state-atom assoc
         :watcher (Thread/currentThread)
         :restart-count 0)
  (while (:keep-running? @state-atom)
    (let [child-exception (atom nil)
          exception-wrapper #(try (thread-fn)
                                  (catch Exception e
                                    (reset! child-exception e)))
          thread (Thread. exception-wrapper)]
      (swap! state-atom assoc :thread thread)
      (.start thread)
      (while (.isAlive thread)
        (.join thread (* 1 1000)))
      (when (:keep-running? @state-atom)
        (swap! state-atom update :restart-count inc)
        (when (not (nil? @child-exception))
          (log/error :fn :thread-watcher
                     :msg "Exception caught in child thread"
                     :ex @child-exception)
          (reset! child-exception nil))
        (log/error :fn :thread-watcher
                   :msg "Thread terminated, restarting"
                   :state @state-atom))))
  (log/info :fn :thread-watcher
            :msg "Thread terminated gracefully"
            :state-atom @state-atom)
  (swap! state-atom assoc :stopped-at (Instant/now)))

(comment
  "Example of using thread-watcher to watch a thread"
  (def state (atom {:keep-running? true}))
  (def t (Thread. (fn [] (thread-watcher (fn []
                                           (Thread/sleep (* 10 1000))
                                           (throw (RuntimeException. "child thread exception!")))
                                         state))))
  (.start t)
  (swap! state assoc :keep-running? false))

(defn shutdown-threads!
  "Gracefully shuts down the threads managed by vrs-registry/-main"
  []
  (swap! consumer-state assoc :keep-running? false)
  (reset! keep-running false))

(defn -main
  "Starts consumption and processing of messages in a separate thread.
   To stop the thread watcher, (swap! consumer-state assoc :keep-running? false)
   To stop the consumer thread, (reset! keep-running false).
   (note: use (shutdown-threads!))
   If the consumer thread is stopped before the thread watcher, it will keep being restarted"
  [& args]
  (start-states!)
  (assert (= :rocksdb-http (:type genegraph.transform.clinvar.cancervariants/cache-db)))
  (log/info :fn ::-main :running-states (mount/running-states))
  (let [batch-limit Long/MAX_VALUE
        batch-counter (atom 0)
        topic-kw :clinvar-raw
        topic-name (-> stream/config :topics topic-kw :name)]
    (stream/initialize-current-offsets!) ; Reads partition_offsets.edn
    (letfn [(initialize-consumer []
              #_"Returns an open and assigned consumer"
              (let [consumer (stream/assigned-consumer-for-topic topic-kw)]
                (doseq [tp (.assignment consumer)]
                  (reset-consumer-position consumer tp))
                consumer))
            (thread-fn []
              #_"Assumes consumer is read to start polling as-is. If running-atom
               is set to false, finishes the current poll batch and  dies."
              (with-open [consumer (initialize-consumer)]
                (while (and @keep-running (<= (swap! batch-counter inc) batch-limit))
                  (when-let [batch (poll-with-retries consumer 60 (* 60 1000))]
                    (when (not-empty batch)
                      (let [time-start (Instant/now)]
                        (dorun (->> batch
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
                    (stream/update-consumer-offsets! consumer [(TopicPartition. topic-name 0)])))))]
      (reset! consumer-state {:keep-running? true})
      (thread-watcher thread-fn consumer-state))))

(defn -main-background
  "Runs -main in a background thread. Useful for running in a REPL.
   Can use (shutdown-threads!) to gracefully terminate that thread"
  [& args]
  (.start (Thread. (partial apply (partial -main) args))))

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

;; curl -X 'PUT' \
;;   'http://localhost:5050/allele' \
;;   -H 'accept: application/json' \
;;   -H 'Content-Type: application/json' \
;;   -d '{
;;   "definition": "NC_000010.11:g.87894077C>T",
;;   "format": "hgvs",
;;   "normalize": "right"
;; }'

(defn anyvar-testing []

  (cp/with-shutdown! [pool (cp/threadpool 50)]
    (let [base-url "http://localhost:5050"
          inp (json/generate-string
               {"definition" "NC_000010.11:g.87894077C>T",
                "format" "hgvs",
                "normalize" "right"})
          headers {"Content-Type" "application/json"
                   "Accept" "application/json"}
          n 1000]
      (let [start (Instant/now)]
        (dorun
         (cp/pmap
          pool
          (fn [i]
            (let [resp (http-client/put (str base-url "/allele")
                                        {:throw-exceptions false
                                         :headers headers
                                         :body inp})]
              (assert (= 200 (:status resp)) {:status (:status resp)
                                              :msg "Request failed"
                                              :resp resp})))
          (range n)))
        (let [duration (Duration/between start (Instant/now))
              avg (.dividedBy duration n)]
          (println (prn-str {:avg (str avg)
                             :total (str duration)})))))))


(defn anyvar-libpython []
  (cp/with-shutdown! [pool (cp/threadpool 20)]
    (libpython-clj2.python/initialize!)
    (require-python '[anyvar.restapi.globals])
    (require-python '[anyvar.anyvar])
    (let [base-url "http://localhost:5050"
          inp (json/generate-string
               {"definition" "NC_000010.11:g.87894077C>T",
                "format" "hgvs",
                "normalize" "right"})
          av (anyvar.anyvar/make_anyvar)
          translator (py/py.- av translator)
          n 1000]
      (let [start (Instant/now)]
        (dorun
         (cp/pmap
          pool
          (fn [i]
            (let [resp (py/->jvm (py/py. translator
                                         translate_from
                                         "NC_000010.11:g.87894077C>T"
                                         :fmt "hgvs"))]))
          (range n)))
        (let [duration (Duration/between start (Instant/now))
              avg (.dividedBy duration n)]
          (println (prn-str {:avg (str avg)
                             :total (str duration)})))))))

(libpython-clj2.python/initialize!)
(require-python '[anyvar.restapi.globals])
(require-python '[anyvar.anyvar])
(def av (anyvar.anyvar/make_anyvar))


#_(time (dotimes [n 100] (py/->jvm (py/py. (py/py.- av translator) translate_from "NM_000551.3:c.1A>T" :fmt "hgvs"))))
