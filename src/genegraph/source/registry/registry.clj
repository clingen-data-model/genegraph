(ns genegraph.source.registry.registry)

(defprotocol Registry
  "A mutable set of associations between keys and values"
  (get-key [this k])
  (put-key! [this k v])
  (delete-key! [this k]))


#_(extend-type org.rocksdb.RocksDB
    registry/Registry
    (get-key [db k]
      (let [k (nippy/fast-freeze k)
            v (rocksdb/rocks-get-raw-key db k)]
        (when (not= :genegraph.rocksdb/miss v) v)))
    (put-key! [db k v]
      (let [k (nippy/fast-freeze k)]
        (rocksdb/rocks-put-raw-key! db k v))
      db)
    (delete-key!
      [db k]
      (let [k (nippy/fast-freeze k)]
        (rocksdb/rocks-delete-raw-key! db k)))


    prefixable-registry/RegistryPrefixability
    (prefixable-serialization [db k]
      (cond (string? k) (.getBytes k)
            (keyword? k) (prefixable-registry/prefixable-serialization db (str k))
            :else (throw (ex-info "Only string/keyword keys are supported"
                                  {:k k} (IllegalArgumentException.)))))

    prefixable-registry/Registry
    (get-key [db k]
      (let [k (prefixable-registry/prefixable-serialization db k)
            v (rocksdb/rocks-get-raw-key db k)]
        (when (not= :genegraph.rocksdb/miss v) v)))
    (put-key! [db k v]
      (let [k (prefixable-registry/prefixable-serialization db k)]
        (rocksdb/rocks-put-raw-key! db k v))
      db)
    (delete-key!
      [db k]
      (let [k (prefixable-registry/prefixable-serialization db k)]
        (rocksdb/rocks-delete-raw-key! db k))))


(comment
  (with-open [db (rocksdb/open "test1.db")]
    (registry/put-key! db [[:a 1]] "val1")
    (registry/put-key! db "key2" "val2")
    (registry/put-key! db "key3" "val3")
    (test/is (= "val1" (registry/get-key db "key1")))
    (test/is (= "val2" (registry/get-key db "key2")))
    (test/is (= "val3" (registry/get-key db "key3")))
    ;; Test these keys cannot be used with a prefixable registry
    (test/is (= nil (prefixable-registry/get-key db "key1")))
    (test/is (= nil (prefixable-registry/get-key db "key2")))
    (test/is (= nil (prefixable-registry/get-key db "key3"))))

  (with-open [db (rocksdb/open "test2.db")]
    (prefixable-registry/put-key! db "key1" "val1")
    (prefixable-registry/put-key! db "key2" "val2")
    (prefixable-registry/put-key! db "key3" "val3")

    (test/is (= "val1" (prefixable-registry/get-key db "key1")))
    (test/is (= "val2" (prefixable-registry/get-key db "key2")))
    (test/is (= "val3" (prefixable-registry/get-key db "key3")))
    ;; Test these keys cannot be used with normal registry
    (test/is (= nil (registry/get-key db "key1")))
    (test/is (= nil (registry/get-key db "key2")))
    (test/is (= nil (registry/get-key db "key3")))))


#_(extend-protocol MutableMapping
    org.rocksdb.RocksDB
    (get-key [db k]
      (let [v (rocksdb/rocks-get-raw-key db k)]
        (when (not= :genegraph.rocksdb/miss v) v)))
    (put-key! [db k v]
      (rocksdb/rocks-put-raw-key! db k v)
      db)
    (delete-key!
      [db k]
      (rocksdb/rocks-delete-raw-key! db k)))

#_(extend-protocol PrefixableSerialization
    String
    (prefixable-serialization [s]
      (-> s .getBytes)))


(comment
  (def db-path "test2.db")
  (rocksdb/rocks-destroy! db-path)
  (with-open [registry (proxy [PrefixableRocksDBRegistryRecord] []
                         (prefixable-key [this k]
                           (proxy-super prefixable-key this k)
                           (cond (string? k) (.getBytes k)
                                 (keyword? k) (prefixable-key this (str k))
                                 (map? k) ()
                                 :else (throw (ex-info "Only string keys are supported"
                                                       {:k k} (IllegalArgumentException.))))))
              #_#_registry (PrefixableRocksDBRegistry. (rocksdb/open db-path))]
    #_(doto registry
        (put-key! "mykey1" "myval1")
        (put-key! "mykey2" "myval2")
        (put-key! "mykey3" "myval3")
        (put-key! "mykey4" "myval4")
        (put-key! :D 4)
        (put-key! :E {:somekey {:F :Fval}}))))

(comment
  (def db-path "test2.db")
  (rocksdb/rocks-destroy! db-path)
  (with-open [registry (PrefixableRocksDBRegistry. (rocksdb/open db-path))]
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
