(ns genegraph.sink.local-event-recorder
  "Store the outcome of events to a local database. makes it possible
  to compare current code changes against a previous build."
  (:require [genegraph.rocksdb :as rocksdb]
            [mount.core :refer [defstate]]
            [genegraph.env :as env]
            [genegraph.util.fs :as fs])
  (:import [java.nio ByteBuffer]))

(defn- topic-database-path [topic]
  (str env/data-vol "/event-records/" topic))

(defn- put-event!
  "Store an event in database, using sink offset as key"
  [db event]
  (let [k (-> (ByteBuffer/allocate Long/BYTES)
              (.putLong (:genegraph.sink.stream/offset event))
              .array)] 
    (rocksdb/rocks-put-raw-key! db k event)))

(defstate event-recorders
  :start (do
           (fs/ensure-target-directory-exists!
            (str (env/data-vol) "/event-records"))
           (reduce (fn [dbs topic]
                      (assoc dbs
                             (keyword topic)
                             (rocksdb/open (topic-database-path topic))))
                   {}
                   )))
