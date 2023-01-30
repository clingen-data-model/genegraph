(ns genegraph.source.registry.redis
  (:require [genegraph.rocksdb :as rocksdb]
            [genegraph.transform.clinvar.common :refer [with-retries]]
            [io.pedestal.log :as log]
            [taoensso.carmine :as car]))

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
                (cond-> rs
                  (not= 0 next-cursor)
                  (lazy-cat (get-batch next-cursor)))))]
      (lazy-seq (get-batch 0)))))

(defn ->sequential
  "If X is not sequential, wraps it in a vector"
  [X]
  (cond-> X
    (not (sequential? X)) (vector)))

(defn get-keys-pipelined
  "Calls carmine/get on every value in `ks` within one carmine/wcar call.
   If `ks` is sequential, values are returned in the same order.
   e.g. opts [:a :b :c] ->
   (car/wcar opts (car/get :a) (car/get :b) (car/get :c))"
  [opts ks]
  (->> (concat ['car/wcar opts]
               (mapv #(list car/get %) ks))
       (apply list)    ; Make it an evaluatable macro call
       eval            ; Expand the macro and execute the resulting code
       ->sequential))  ; wcar returns single item if only 1 command is sent, undo that

(comment
  "Example of using the carmine pipelining macro and get-keys-pipelined"
  (def redis-opts {:spec {:uri "redis://localhost:6380"}})
  (->> ["a"]
       (take 1)
       (get-keys-pipelined redis-opts)))

(comment
  "Example of adding some data and using get and scan"
  (def conn {:pool {} :spec {:uri "redis://localhost:6379/"}})

  (defmacro wcar* [& body] `(car/wcar conn ~@body))
  (wcar* (car/ping)
         (dorun (map #(car/set (str "key" %) (str "val" %)) (range 20))))

  (wcar* (car/get "key1"))

  (wcar* (car/scan 0 "match" "key*")))
