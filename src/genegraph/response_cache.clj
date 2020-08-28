(ns genegraph.response-cache
  (:require [genegraph.rocksdb :as rocksdb]
            [mount.core :refer [defstate] :as mount]
            [io.pedestal.interceptor.chain :refer [terminate]]
            [io.pedestal.log :as log]))

(def db-name "response_cache")

(defstate cache-store
  :start (rocksdb/open db-name)
  :stop (rocksdb/close cache-store))

(defn running? []
  ((mount/running-states) (str #'cache-store)))

(defn check-for-cached-response [context]
  (let [body (get-in context [:request :body])]
    (if (running?)
      (let [cached-response (rocksdb/rocks-get cache-store body)]
        (if (= ::rocksdb/miss cached-response)
          (do 
            (log/debug :fn ::check-for-cached-response :msg "request cache miss!")
            context)
          (do
            (log/debug :fn ::check-for-cached-response :msg "request cache hit")
            (-> context
                (assoc :response cached-response)
                terminate))))
      context)))

(defn store-processed-response [context]
  (if (running?)
    (log/debug :fn ::store-processed-response :msg "storing processed response")
    (rocksdb/rocks-put! cache-store 
                        (get-in context [:request :body])
                        (:response context)))
  context)

(defn response-cache-interceptor []
  {:name ::response-cache
   :enter check-for-cached-response
   :leave store-processed-response})

(defn clear-response-cache! []
  (mount/stop #'cache-store)
  (rocksdb/rocks-destroy! db-name)
  (mount/start #'cache-store))
