(ns genegraph.rocksdb
  (:require [genegraph.env :as env]
            [taoensso.nippy :as nippy :refer [freeze thaw]])
  (:import (org.rocksdb RocksDB Options)))


(defn open [path]
  (let [full-path (str env/data-vol "/" path )
        opts (-> (Options.)
                 (.setCreateIfMissing true))]
    ;; todo add logging...
    (RocksDB/open opts full-path)))

(defn rocks-put! [db k v]
  (.put db (freeze k) (freeze v)))

(defn rocks-delete! [db k]
  (.delete db (freeze k)))

(defn rocks-get [db k]
  (when-let [result (.get db (freeze k))]
    (thaw result)))

(defn close [db]
  (.close db))
