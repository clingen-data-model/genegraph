(ns genegraph.rocksdb
  (:require [genegraph.env :as env]
            [taoensso.nippy :as nippy :refer [freeze thaw]]
            [digest])
  (:import (org.rocksdb RocksDB Options)))


(defn open [path]
  (let [full-path (str env/data-vol "/" path )
        opts (-> (Options.)
                 (.setCreateIfMissing true))]
    ;; todo add logging...
    (RocksDB/open opts full-path)))

(defn key-digest [k]
  (-> k freeze digest/md5 .getBytes))

(defn rocks-put! [db k v]
  (.put db (key-digest k) (freeze v)))

(defn rocks-delete! [db k]
  (.delete db (key-digest k)))

(defn rocks-get [db k]
  (when-let [result (.get db (key-digest k))]
    (thaw result)))

(defn close [db]
  (.close db))
