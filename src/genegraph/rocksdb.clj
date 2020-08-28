(ns genegraph.rocksdb
  (:require [genegraph.env :as env]
            [taoensso.nippy :as nippy :refer [freeze thaw]]
            [digest])
  (:import (org.rocksdb RocksDB Options)))


(defn create-db-path [db-name] 
  (str env/data-vol "/" db-name))

(defn open [db-name]
  (let [full-path (create-db-path db-name)
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

(defn rocks-destroy! [db-name]
  (RocksDB/destroyDB (create-db-path db-name) (Options.)))

(defn rocks-get [db k]
  (if-let [result (.get db (key-digest k))]
    (thaw result)
    ::miss))

(defn close [db]
  (.close db))
