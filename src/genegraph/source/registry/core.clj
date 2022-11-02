(ns genegraph.source.registry.core
  (:require [genegraph.rocksdb :as rocksdb]
            [genegraph.rocksdb :as rocks]
            [io.pedestal.log :as log]
            [taoensso.nippy :as nippy]
            [clojure.test :as test]))

(defprotocol MutableMapping
  "A mutable set of associations between keys and values"
  (get-key [this k])
  (put-key! [this k v])
  (delete-key! [this k]))

(defprotocol PrefixableAssociative
  "An associative collection whose values can be retrieved by key prefixes"
  (prefix-iterator [this prefix]
    "Return an iterator over the set of values whose keys begin with prefix"))

;; Implements MutableMapping, Closeable, PrefixableAssociative.
;; Prefix scans only work if the keys are orderable after nippy/fast-freeze.
;; TODO add a PrefixableAssociative protocol fn for
;; prefixable serialization of arbitrary keys
(deftype RocksDBRegistry [db-handle]
  MutableMapping
  (get-key [this k]
    (let [v (rocksdb/rocks-get-raw-key db-handle (nippy/fast-freeze k))]
      (when (not= :genegraph.rocksdb/miss v) v)))
  (put-key! [this k v]
    (rocksdb/rocks-put-raw-key! db-handle (nippy/fast-freeze k) v)
    this)
  (delete-key! [this k]
    (rocksdb/rocks-delete-raw-key! db-handle (nippy/fast-freeze k)))

  PrefixableAssociative
  (prefix-iterator [this prefix]
    (-> (rocksdb/raw-prefix-iter db-handle (nippy/fast-freeze prefix))
        (rocksdb/rocks-entry-iterator-seq)
        (.iterator)))

  java.io.Closeable
  (close [this]
    (rocksdb/close db-handle)))

(comment
  (def db-path "test2.db")
  (rocks/rocks-destroy! db-path)
  (with-open [registry (RocksDBRegistry. (rocksdb/open db-path))]
    (doto registry
      (put-key! "mykey1" "myval1")
      (put-key! "mykey2" "myval2")
      (put-key! "mykey3" "myval3")
      (put-key! "mykey4" "myval4")
      (put-key! :D 4)
      (put-key! :E {:somekey {:F :Fval}}))

    (test/is (= "myval3" (get-key registry "mykey3")))
    (test/is (= 4 (get-key registry :D)))
    (test/is (= {:somekey {:F :Fval}} (get-key registry :E)))
    (delete-key! registry "mykey3")
    (test/is (= nil (get-key registry "mykey3")))
    (test/is (= 4 (get-key registry :D)))

    (let [iter (prefix-iterator registry "mykey")]
      (into [] (iterator-seq iter)))))
