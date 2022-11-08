(ns genegraph.source.registry.redis
  (:require [genegraph.rocksdb :as rocksdb]
            [io.pedestal.log :as log]
            [taoensso.carmine :as car]))

;; Carmine uses memoization to store the threadpool for each connection config.
;; This might leave dangling threads in the pool even when the application code
;; has finished. Should look into that.
;; https://github.com/ptaoussanis/carmine/issues/224
;; https://github.com/ptaoussanis/carmine/issues/266
;; Might be able to get the handle to the threadpool by calling
;; taoensso.carmine.connections/conn-pool.
;; Might also be able to kick the threadpool out of hte memoize cache
;; by sending the db spec with :mem/del as the first vararg.
;; http://ptaoussanis.github.io/encore/taoensso.encore.html#var-memoize
(def conn {:pool {} :spec {:uri "redis://localhost:6379/"}})

(defmacro wcar* [& body] `(car/wcar conn ~@body))

(wcar* (car/ping)
       (dorun (map #(car/set (str "key" %) (str "val" %)) (range 20))))

(wcar* (car/get "key1"))

(wcar* (car/scan 0 "match" "key*"))

(defn default-serializer
  "Takes a key of arbitrary data, return byte array (or maybe string).
   Special cases:
   If k is a vector, perform a multipart digest on its elements.
   If k is a string, return it with no further changes."
  [k]
  ;; [:keyname :value1]
  ;; (iterate-prefix :key) -> [:value1]
  (cond (bytes? k) k
        (string? k) k #_(.getBytes k)
        (keyword? k) (default-serializer (str k))
        (vector? k) (rocksdb/multipart-key-digest k)
        :else (rocksdb/key-digest k)))

(defn connectable?
  "Returns false if the server information in conn cannot be connected to"
  [conn]
  (try
    (= "PONG" (car/wcar conn (car/ping)))
    (catch Exception e
      (log/debug :fn :connectable?
                 :ex-message (ex-message e)
                 :ex-data (ex-data e)
                 :exception e)
      false)))

(defn put-key
  "Takes a Carmine redis connection map, a key, and a value.
   Key can be any data type supported by default-serializer"
  [conn k v & {:keys [key-serializer]
               :or {key-serializer default-serializer}}]
  (car/wcar conn (car/set (key-serializer k) v)))

(defn get-key
  "Takes a Carmine redis connection map, and a key. Returns value, or nil.
   Key can be any data type supported by default-serializer"
  [conn k & {:keys [key-serializer]
             :or {key-serializer default-serializer}}]
  (car/wcar conn (car/get (key-serializer k))))

(defn flushall
  "Removes all entries from the db"
  [conn]
  (car/wcar conn (car/flushall)))

;;(put-key conn "key1" "val1")

#_(time (dorun (pmap #(put-key conn (str "key" %) (str "val" %)) (range 1000))))
#_(time (def result-vals (car/wcar conn
                                   (into '() (pmap #(get-key conn (str "key" %))
                                                   (range 100))))))