(ns genegraph.rocksdb
  (:require [genegraph.env :as env]
            [taoensso.nippy :as nippy :refer [freeze thaw]]
            [digest])
  (:import (org.rocksdb RocksDB Options ReadOptions Slice RocksIterator)
           java.security.MessageDigest))


(defn create-db-path [db-name] 
  (str env/data-vol "/" db-name))

(defn open [db-name]
  (let [full-path (create-db-path db-name)
        opts (-> (Options.)
                 (.setCreateIfMissing true))]
    ;; todo add logging...
    (RocksDB/open opts full-path)))

(defn- key-digest [k]
  (-> k freeze digest/md5 .getBytes))

(defn- key-tail-digest [k]
  (let [key-byte-array (-> k freeze digest/md5 .getBytes)
        last-byte-idx (- (count key-byte-array) 1)
        last-byte (aget key-byte-array last-byte-idx)]
    (aset-byte key-byte-array last-byte-idx (+ 1 last-byte))
    key-byte-array))

(defn- multipart-key-digest [ks]
  (->> ks (map #(-> % freeze digest/md5)) (apply str) .getBytes))

(defn rocks-put!
  "Put v in db with key k. Key will be frozen with md5 hash.
   Suitable for keys large and small with arbitrary Clojure data,
   but breaks any expectation of ordering. Value will be frozen,
   supports arbitrary Clojure data."
  [db k v]
  (.put db (key-digest k) (freeze v)))

(defn rocks-put-raw-key!
  "Put v in db with key k. Key will be used without freezing and must be
   a Java byte array. Intended to support use cases where the user must
   be able to define the ordering of data. Value will be frozen."
  [db k v]
  (.put db k (freeze v)))

(defn rocks-get-raw-key!
  "Retrieve data that has been stored with a byte array defined key. K must be a
  Java byte array."
  [db k]
  (if-let [result (.get db (key-digest k))]
    (thaw result)
    ::miss))

(defn rocks-put-multipart-key! 
  "ks is a sequence, will hash each element in ks independently to support 
   range scans based on different elements of the key"
  [db ks v]
  (.put db (multipart-key-digest ks) (freeze v)))

(defn rocks-delete! [db k]
  (.delete db (key-digest k)))

(defn rocks-delete-multipart-key! [db ks]
  (.delete db (multipart-key-digest ks)))

(defn rocks-destroy!
  "Delete the named instance. Database must be closed prior to this call"
  [db-name]
  (RocksDB/destroyDB (create-db-path db-name) (Options.)))

(defn rocks-get
  "Get and thaw element with key k. Key may be any arbitrary Clojure datatype"
  [db k]
  (if-let [result (.get db (key-digest k))]
    (thaw result)
    ::miss))

(defn rocks-get-multipart-key
  "Get and thaw element with key sequence ks. ks is a sequence of
  arbitrary Clojure datatypes."
  [db ks]
  (if-let [result (.get db (multipart-key-digest ks))]
    (thaw result)
    ::miss))

(defn rocks-delete-with-prefix!
  "use RocksDB.deleteRange to clear keys starting with prefix"
  [db prefix]
  (.deleteRange db (key-digest prefix) (key-tail-digest prefix)))

(defn close
  "Close the database"
  [db]
  (.close db))

(defn prefix-iter
  "return a RocksIterator that covers records with prefix"
  [db prefix]
  (let [iter (.newIterator db 
                           (.setIterateUpperBound (ReadOptions.)
                                                  (Slice. (key-tail-digest prefix))))]
    (.seek iter (key-digest prefix))
    iter))

(defn entire-db-iter
  "return a RocksIterator that iterates over the entire database"
  [db]
  (doto (.newIterator db)
    (.seekToFirst)))

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

(defn entire-db-seq
  "Return a lazy-seq over all records in the database"
  [db]
  (-> db entire-db-iter rocks-iterator-seq))

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
