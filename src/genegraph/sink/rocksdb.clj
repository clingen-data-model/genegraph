(ns genegraph.sink.rocksdb
  "Support for using a RocksDB as both a source of events as well as a sink 
   for events supporting side-events. In general this will be of greatest
   utility during testing and development, as it will be possible to record
   and replay the outcomes of events."
  (:require [genegraph.rocksdb :as rocksdb]
            [mount.core :refer [defstate]]
            [genegraph.env :as env]
            [clojure.string :as s])
  (:import [java.nio ByteBuffer]))

(defn open-for-topic! [topic]
  (rocksdb/open topic))

(defn close-for-topic! [topic]
  (rocksdb/close topic))

(defn put!
  "Store an event in database, using sink offset as key"
  [db event]
  (let [k (-> (ByteBuffer/allocate Long/BYTES)
              (.putLong (:genegraph.sink.stream/offset event))
              .array)] 
    (rocksdb/rocks-put-raw-key! db k event)))
