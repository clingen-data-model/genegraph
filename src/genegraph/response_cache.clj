(ns genegraph.response-cache
  (:require [genegraph.rocksdb :as rocksdb]
            [mount.core :refer [defstate]]
            [io.pedestal.interceptor.chain :refer [terminate]]
            [io.pedestal.log :as log]))

(defstate cache-store
  :start (rocksdb/open "response_cache")
  :stop (rocksdb/close cache-store))

(defn check-for-cached-response [context]
  ;; (println (keys context))
  (let [body (get-in context [:request :body])]
    (let [cached-response (rocksdb/rocks-get cache-store body)]
      (if (= ::rocksdb/miss cached-response)
        (do 
          (log/debug :fn ::check-for-cached-response :msg "request cache miss!")
          context)
        (do
          (log/debug :fn ::check-for-cached-response :msg "request cache hit")
          (-> context
              (assoc :response cached-response)
              terminate))))))

(defn store-processed-response [context]
  (log/debug :fn ::store-processed-response :msg "storing processed response")
  (rocksdb/rocks-put! cache-store 
                      (get-in context [:request :body])
                      (:response context))
  context)

(defn response-cache-interceptor []
  {:name ::response-cache
   :enter check-for-cached-response
   :leave store-processed-response})
