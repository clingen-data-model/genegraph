(ns genegraph.rocksdb
  (:require [genegraph.env :as env]
            [taoensso.nippy :as nippy :refer [freeze thaw]]
            [digest])
  (:import (org.rocksdb RocksDB Options ReadOptions Slice)
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

(defn prefix-iter [db prefix]
  (let [iter (.newIterator db 
                           (.setIterateUpperBound (ReadOptions.)
                                                  (Slice. (key-tail-digest prefix))))]
    (.seek iter (key-digest prefix))
    iter))

(defn rocks-iterator-seq [iter]
  (lazy-seq 
   (if (.isValid iter)
     (let [v (thaw (.value iter))]
       (.next iter)
       (cons v
             (rocks-iterator-seq iter)))
     nil)))

(defn prefix-seq 
  "Return a lazy-seq over all records beginning with prefix"
  [db prefix]
  (-> db (prefix-iter prefix) rocks-iterator-seq))

(defn sample-prefix 
  "Take first n records from db given prefix"
  [db prefix n]
  (let [iter (prefix-iter db prefix)]
    (loop [i 0
           ret (transient [])]
      (if (and (< i n) (.isValid iter))
        (let [new-ret (conj! ret (-> iter .value thaw))] 
          (.next iter)
          (recur (inc i) new-ret))
        (do 
          (.close iter)
          (persistent! ret))))))
