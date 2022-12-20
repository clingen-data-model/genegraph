(ns genegraph.source.registry.redis
  (:require [genegraph.rocksdb :as rocksdb]
            [genegraph.transform.clinvar.common :refer [with-retries]]
            [io.pedestal.log :as log]
            [taoensso.carmine :as car]
            [taoensso.carmine.connections :as car-conn]))

;; Carmine uses memoization to store the threadpool for each connection config.
;; This might leave dangling threads in the pool even when the application code
;; has finished. Should look into that.
;; https://github.com/ptaoussanis/carmine/issues/224
;; https://github.com/ptaoussanis/carmine/issues/266
;; Might be able to get the handle to the threadpool by calling
;; taoensso.carmine.connections/conn-pool.
;; Might also be able to kick the threadpool out of the memoize cache
;; by sending the db spec with :mem/del as the first vararg.
;; http://ptaoussanis.github.io/encore/taoensso.encore.html#var-memoize
;; Resolve with an explicitly defined threadpool:
;; https://github.com/ptaoussanis/carmine/commit/a1d0c4ec1dd4848a9323eaa149ab284509664515


(defn make-connection-pool
  "connections/conn-pool supports :mem/del and :mem/fresh via encore/cache
   https://github.com/ptaoussanis/encore/blob/0034c80e5caea4cf1d413c5eb918798761e40570/src/taoensso/encore.cljc#L2475"
  [pool-opts]
  (car-conn/conn-pool :mem/fresh pool-opts))

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


(defn key-seq
  "Returns a lazy seq over the keys in the db specified by conn.
   Uses default SCAN COUNT argument."
  [conn & {:keys [scan-count] :or {scan-count 1000}}]
  (let [total (atom 0)]
    (letfn [(get-batch [cursor]
              (let [[next-cursor rs]
                    (with-retries 12 5000 #(car/wcar conn (car/scan cursor "COUNT" scan-count)))
                    next-cursor (parse-long next-cursor)]
                (reset! total (+ @total (count rs)))
                (println {:next-cursor next-cursor :rs-count (count rs) :total @total})
                (cond-> rs
                  (not= 0 next-cursor)
                  (lazy-cat (get-batch next-cursor)))))]
      (lazy-seq (get-batch 0)))))


(comment
  (def conn {:pool {} :spec {:uri "redis://localhost:6379/"}})

  (defmacro wcar* [& body] `(car/wcar conn ~@body))
  (wcar* (car/ping)
         (dorun (map #(car/set (str "key" %) (str "val" %)) (range 20))))

  (wcar* (car/get "key1"))

  (wcar* (car/scan 0 "match" "key*")))

(comment
  #_(put-key conn "key1" "val1")

  #_(time (dorun (pmap #(put-key conn (str "key" %) (str "val" %)) (range 1000))))
  #_(time (def result-vals (car/wcar conn
                                     (into '() (pmap #(get-key conn (str "key" %))
                                                     (range 100)))))))
