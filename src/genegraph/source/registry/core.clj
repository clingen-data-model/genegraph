(ns genegraph.source.registry.core
  (:require [clojure.test :as test]
            [genegraph.rocksdb :as rocksdb]
            [mount.core :as mount]))



(defn default-serializer
  "Takes a key, maybe multipart vector, return byte array"
  [k]
  ;; [:keyname :value1]
  ;; (iterate-prefix :key) -> [:value1]

  (cond (bytes? k) k
        (string? k) (.getBytes k)
        (keyword? k) (default-serializer (str k))
        (vector? k) (rocksdb/multipart-key-digest k)
        :else (rocksdb/key-digest k)))

#_(mount/defstate db
    :start (fn [] {:connection (rocksdb/open "test1.db")})
    :stop (rocksdb/close (:connection db)))

(def redis-conn {:pool {#_(comment Default pool options)}
                 :spec {:uri "redis://localhost:6379/"}})


(mount/defstate db
  :start (fn [] {:connection redis-conn})
  :stop ((:connection db)))

(defmulti get-key #(type (:connection %)))
(defmulti put-key!)
(defmulti delete-key!)
(defmulti delete-prefix!)
(defmulti iterate-prefix!)




#_(defmethod get-key org.rocksdb.RocksDB [db k]
    (let [{:keys [connection key-serializer]} db]
    ;; (rocksdb/rocks-get-multipart)
      (comment
      ;;Do the implementation
        )))

(defmethod get-key 'RedisClient [db k]
  (let [{:keys [connection key-serializer]} db]
    (comment
      ;;Do the implementation
      )))
