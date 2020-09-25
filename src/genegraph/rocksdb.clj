(ns genegraph.rocksdb
  (:require [genegraph.env :as env]
            [taoensso.nippy :as nippy :refer [freeze thaw]]
            [digest])
  (:import (org.rocksdb RocksDB Options)
           java.security.MessageDigest))


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

(defn key-tail-digest [k]
  (let [key-byte-array (-> k freeze digest/md5 .getBytes)
        last-byte-idx (- (count key-byte-array) 1)
        last-byte (aget key-byte-array last-byte-idx)]
    (aset-byte key-byte-array last-byte-idx (+ 1 last-byte))
    key-byte-array))

(defn multipart-key-digest [ks]
  (->> ks (map #(-> % freeze digest/md5)) (apply str) .getBytes))

(defn rocks-put! [db k v]
  (.put db (key-digest k) (freeze v)))

(defn rocks-put-multipart-key! 
  "ks is a sequence, will hash each element in ks independently to support 
   range scans based on different elements of the key"
  [db ks v]
  (.put db (multipart-key-digest ks) (freeze v)))

(defn rocks-delete! [db k]
  (.delete db (key-digest k)))

(defn rocks-delete-multipart-key! [db ks]
  (.delete db (multipart-key-digest ks)))

(defn rocks-destroy! [db-name]
  (RocksDB/destroyDB (create-db-path db-name) (Options.)))

(defn rocks-get [db k]
  (if-let [result (.get db (key-digest k))]
    (thaw result)
    ::miss))

(defn rocks-get-multipart-key [db ks]
  (if-let [result (.get db (multipart-key-digest ks))]
    (thaw result)
    ::miss))

(defn rocks-delete-with-prefix!
  "use RocksDB.deleteRange to clear keys starting with prefix"
  [db prefix]
  (.deleteRange db (key-digest prefix) (key-tail-digest prefix)))

(defn close [db]
  (.close db))
