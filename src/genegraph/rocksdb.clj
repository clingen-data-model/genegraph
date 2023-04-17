(ns genegraph.rocksdb
  (:require [clojure.java.io :as io]
            [digest]
            [genegraph.env :as env]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [taoensso.nippy :as nippy])
  (:import java.util.Arrays
           (org.rocksdb
            Options
            ReadOptions
            RocksDB
            Slice)))

(defn create-db-path [db-name]
  (str env/data-vol "/" db-name))

(defn open [db-name]
  (let [full-path (create-db-path db-name)
        opts (doto (Options.)
               (.setTableFormatConfig
                (doto (org.rocksdb.BlockBasedTableConfig.)
                  (.setCacheIndexAndFilterBlocks true)))
               (.setCreateIfMissing true))]
    (io/make-parents full-path)
    ;; todo add logging...
    (RocksDB/open opts full-path)))

(defn mem-stats
  "Return a map of some memory-related rocksdb properties"
  [db]
  (->> ["rocksdb.block-cache-usage"
        "rocksdb.estimate-table-readers-mem"
        "rocksdb.block-cache-pinned-usage"
        "rocksdb.total-sst-files-size"
        "rocksdb.num-live-versions"
        "rocksdb.live-sst-files-size"]
       (map #(vector % (.getProperty db %)))
       (into {})))

(defn key-digest
  "Return a byte array of the md5 hash of the nippy frozen object"
  [k]
  (-> k nippy/fast-freeze digest/md5 .getBytes))

(defn range-upper-bound
  "Return the key defining the (exclusive) upper bound of a scan,
  as defined by RANGE-KEY"
  [^bytes range-key]
  (let [last-byte-idx (dec (alength range-key))]
    (doto (Arrays/copyOf range-key (alength range-key))
      (aset-byte last-byte-idx (inc (aget range-key last-byte-idx))))))

(defn- key-tail-digest
  [k]
  (-> k nippy/fast-freeze digest/md5 .getBytes range-upper-bound))

(defn multipart-key-digest [ks]
  (->> ks (map #(-> % nippy/fast-freeze digest/md5)) (apply str) .getBytes))

(defn rocks-put!
  "Put v in db with key k. Key will be frozen with md5 hash.
   Suitable for keys large and small with arbitrary Clojure data,
   but breaks any expectation of ordering. Value will be frozen,
   supports arbitrary Clojure data."
  [db k v]
  (.put db (key-digest k) (nippy/fast-freeze v)))

(defn rocks-put-raw-key!
  "Put v in db with key k. Key will be used without freezing and must be
   a Java byte array. Intended to support use cases where the user must
   be able to define the ordering of data. Value will be frozen."
  [db k v]
  (.put db k (nippy/fast-freeze v)))

(defn rocks-get-raw-key
  "Retrieve data that has been stored with a byte array defined key. K must be a
  Java byte array."
  [db k]
  (if-let [result (.get db k)]
    (nippy/fast-thaw result)
    ::miss))

(defn rocks-put-multipart-key!
  "ks is a sequence, will hash each element in ks independently to support
   range scans based on different elements of the key"
  [db ks v]
  (.put db (multipart-key-digest ks) (nippy/fast-freeze v)))

(defn rocks-delete! [db k]
  (.delete db (key-digest k)))

(defn rocks-delete-raw-key!
  "Delete a key."
  [db k]
  (.delete db k))

(defn rocks-delete-multipart-key! [db ks]
  (.delete db (multipart-key-digest ks)))

(defn rocks-destroy!
  "Delete the named instance. Database must be closed prior to this call.
   If db-name is a RocksDB object, this closes it, destroys it, re-opens it"
  [db-name]
  (cond
    (string? db-name) (RocksDB/destroyDB (cond (.startsWith db-name "/") db-name
                                               :else (create-db-path db-name))
                                         (Options.))
    :else (log/error :msg "db-name must be a string")))

(defn rocks-destroy-state!
  "Calls rocks-destroy! on a RocksDB mount/defstate var. If not started, starts it in order to get the
   db path. If it was started when this fn was called, also starts it again before returning."
  [rocksdb-state-var]
  (let [was-running? ((mount/running-states) (str rocksdb-state-var))]
    (when (not was-running?)
      (mount/start rocksdb-state-var))
    (let [db-name (.getName (var-get rocksdb-state-var))]
      (mount/stop rocksdb-state-var)
      (rocks-destroy! db-name))
    (when was-running?
      (mount/start rocksdb-state-var))))

(defn rocks-get
  "Get and nippy/fast-thaw element with key k. Key may be any arbitrary Clojure datatype"
  [db k]
  (if-let [result (.get db (key-digest k))]
    (nippy/fast-thaw result)
    ::miss))

(defn rocks-get-multipart-key
  "Get and nippy/fast-thaw element with key sequence ks. ks is a sequence of
  arbitrary Clojure datatypes."
  [db ks]
  (if-let [result (.get db (multipart-key-digest ks))]
    (nippy/fast-thaw result)
    ::miss))

(defn rocks-delete-with-prefix!
  "use RocksDB.deleteRange to clear keys starting with prefix"
  [db prefix]
  (.deleteRange db (key-digest prefix) (key-tail-digest prefix)))

(defn close
  "Close the database"
  [db]
  (.close db))

(defn raw-prefix-iter
  [db prefix]
  (doto (.newIterator db
                      (.setIterateUpperBound (ReadOptions.)
                                             (Slice. (range-upper-bound prefix))))
    (.seek prefix)))

(defn prefix-iter
  "return a RocksIterator that covers records with prefix"
  [db prefix]
  (raw-prefix-iter db (key-digest prefix)))

(defn entire-db-iter
  "return a RocksIterator that iterates over the entire database"
  [db]
  (doto (.newIterator db)
    (.seekToFirst)))

(defn rocks-iterator-seq [iter]
  (lazy-seq
   (if (.isValid iter)
     (let [v (nippy/fast-thaw (.value iter))]
       (.next iter)
       (cons v
             (rocks-iterator-seq iter)))
     nil)))

(defn rocks-entry-iterator-seq
  "Iterators pin blocks they iterate into in memory.
   When garbage collected these will be cleared, but "
  [^org.rocksdb.RocksIterator iter]
  (lazy-seq
   (if (.isValid iter)
     (let [k (.key iter)
           v (nippy/fast-thaw (.value iter))]
       (.next iter)
       (cons [k v]
             (rocks-entry-iterator-seq iter)))
     nil)))

#_(defn rocks-entry-iterator-seq
    "Iterators pin blocks they iterate into in memory.
   When garbage collected these will be cleared, but "
    [^org.rocksdb.RocksIterator iter]
    (if (.isValid iter)
      (let [k (.key iter)
            v (nippy/fast-thaw (.value iter))]
        (.next iter)
        (lazy-cat [[k v]]
                  (rocks-entry-iterator-seq iter)))
      nil))

(defn entire-db-entry-seq [^RocksDB db]
  (-> db entire-db-iter rocks-entry-iterator-seq))

(defn prefix-seq
  "Return a lazy-seq over all records beginning with prefix"
  [db prefix]
  (-> db (prefix-iter prefix) rocks-iterator-seq))

(defn entire-db-seq
  "Return a lazy-seq over all records in the database"
  [db]
  (-> db entire-db-iter rocks-iterator-seq))

(defn raw-prefix-seq
  "Return a lazy-seq over all records in DB beginning with PREFIX,
  where prefix is a byte array. Intended when record ordering
  is significant."
  [db prefix]
  (-> db (raw-prefix-iter prefix) rocks-iterator-seq))

(defn raw-prefix-entry-seq
  "Return a lazy-seq over all records in DB beginning with PREFIX,
  where prefix is a byte array. Intended when record ordering
  is significant.
   Returns key-value pairs [key value]."
  [db prefix]
  (-> db (raw-prefix-iter prefix) rocks-entry-iterator-seq))

(defn sample-prefix
  "Take first n records from db given prefix"
  [db prefix n]
  (let [iter (prefix-iter db prefix)]
    (loop [i 0
           ret (transient [])]
      (if (and (< i n) (.isValid iter))
        (let [new-ret (conj! ret (-> iter .value nippy/fast-thaw))]
          (.next iter)
          (recur (inc i) new-ret))
        (do
          (.close iter)
          (persistent! ret))))))
