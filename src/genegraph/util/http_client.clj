(ns genegraph.util.http-client
  (:require [genegraph.rocksdb :as rocksdb]
            [io.pedestal.log :as log]
            [clj-http.client :as http])
  (:import (org.rocksdb RocksDB)))

(defn http-get-with-cache
  "Performs an http get, storing the response in rocksdb and retrieving from there on future requests with the same parameters.
  Drops the :http-client clj-http response field."
  [^RocksDB cache-db url & [req & r]]
  (try
    (let [key-obj {:url url :req req :r r}
          cached-value (rocksdb/rocks-get cache-db key-obj)]
      (if (not= ::rocksdb/miss cached-value)
        (do (log/debug :fn ::http-get-with-cache :cache-status :hit)
            cached-value)
        (let [response (dissoc (apply http/get [url req r]) :http-client)]
          (log/debug :fn ::http-get-with-cache :cache-status :miss)
          (log/debug :msg "Caching response" :key-obj key-obj :response response)
          (rocksdb/rocks-put! cache-db key-obj response)
          response)))
    #_(finally (rocksdb/close cache-db))))
