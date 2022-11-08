(ns genegraph.util.http-client
  (:require [genegraph.rocksdb :as rocksdb]
            [io.pedestal.log :as log]
            [clj-http.client :as http])
  (:import (org.rocksdb RocksDB)))

(defn http-get-with-cache
  "Performs an http get, storing the response in rocksdb and retrieving from there on future requests with the same parameters.
   Drops the :http-client clj-http response field.
   The parameters for clj-http.client/get are a url plus the parameters for ring-clojure handlers.
   URL, request options, response callback fn (for async requests), exception callback fn (for async requests).
   clj-http.client/get parses the URL and fills in the request map with the appropriate method/host/proto/port/etc
   https://github.com/ring-clojure/ring/blob/8af4ab93190dfe5b4827c14b416a4cb92e18cdaf/SPEC"
  [^RocksDB cache-db url & [req-opts & [rcb ecb]]]
  (log/debug :url url :req req-opts :rcb rcb :ecb ecb)
  (let [key-obj {:url url :req-opts req-opts}
        cached-value (rocksdb/rocks-get cache-db key-obj)]
    (if (not= ::rocksdb/miss cached-value)
      (do (log/info :fn ::http-get-with-cache :cache-status :hit)
          cached-value)
      (let [response (dissoc (apply http/get [url req-opts rcb ecb]) :http-client)]
        (log/info :fn ::http-get-with-cache :cache-status :miss)
        (log/debug :msg "Caching response" :key-obj key-obj :response response)
        (rocksdb/rocks-put! cache-db key-obj response)
        response))))
