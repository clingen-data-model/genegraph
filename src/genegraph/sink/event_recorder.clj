(ns genegraph.sink.event-recorder
  "Store the outcome of events to a local database. makes it possible
  to compare current code changes against a previous build."
  (:require [genegraph.rocksdb :as rocksdb]
            [mount.core :refer [defstate]]
            [genegraph.env :as env]
            [io.pedestal.log :as log])
  (:import [java.time Instant]
           java.nio.ByteBuffer))

(defstate event-database
  :start (rocksdb/open "event_database")
  :stop (rocksdb/close event-database))

(defn event-key
  "Create a key for the given event."
  [event]
  (let [event-format-str (str (:genegraph.annotate/format event))
        key-buffer (ByteBuffer/allocate (+ (* 2 Long/BYTES) ; Partition and offset
                                           (count event-format-str)))]
    (-> key-buffer
        (.put (.getBytes event-format-str))
        (.putLong (or (:genegraph.sink.stream/partition event) 0))
        (.putLong (or (:genegraph.sink.stream/offset event)
                      (.toEpochMilli (Instant/now))))
        .array)))

(defn- record-event!
  "Store an event in database, using sink offset as key.
  Returns event, in case further processing on stream is needed."
  [event]
  (if (::record-event event)
    (try 
      (rocksdb/rocks-put-raw-key! 
       event-database
       (event-key event)
       (-> event
           (dissoc :io.pedestal.interceptor.chain/stack ; remove non-freezable keys
                   :io.pedestal.interceptor.chain/execution-id)))
      event
      (catch Exception e
        (log/error :fn ::record-event!
                   :msg "Could not store event in recorder")
        (.printStackTrace e)
        (assoc event ::exception e)))
    event))

(def record-event-interceptor
  {:name ::record-event
   :leave record-event!})


