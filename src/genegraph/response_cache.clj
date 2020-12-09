(ns genegraph.response-cache
  (:require [genegraph.rocksdb :as rocksdb]
            [genegraph.env :as env]
            [mount.core :refer [defstate] :as mount]
            [io.pedestal.interceptor.chain :refer [terminate]]
            [io.pedestal.log :as log]
            [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]]))

(def db-name "response_cache")

(def expiration-notification-chan (atom (chan (a/dropping-buffer 1))))

(declare cache-store)

(defn running? []
  (and env/use-response-cache ((mount/running-states) (str #'cache-store))))

(defn clear-response-cache! []
  (log/info :fn ::clear-respsonse-cache! :msg "clearing response cache")
  (when (running?)
    (mount/stop #'cache-store)
    (try
      (rocksdb/rocks-destroy! db-name)
      (catch Exception e
        (log/info :fn clear-response-cache! :msg (str "Caught exception: " (.getMessage e)) :exception e)))
    (mount/start #'cache-store)))

(defstate cache-store
  :start (when env/use-response-cache
           (reset! expiration-notification-chan (chan (a/dropping-buffer 1)))
           (.start (Thread. #(while (<!! @expiration-notification-chan)
                               (clear-response-cache!))))
           (rocksdb/open db-name))
  :stop (when env/use-response-cache
          (close! @expiration-notification-chan)
          (rocksdb/close cache-store)))


(defn check-for-cached-response [context]
  (let [body (get-in context [:request :body])]
    (println " checking for cached response ")
    (println body)
    (if (running?)
      (let [cached-response (rocksdb/rocks-get cache-store body)]
        (if (= ::rocksdb/miss cached-response)
          (do 
            (log/info :fn ::check-for-cached-response :msg "request cache miss!")
            context)
          (do
            (log/info :fn ::check-for-cached-response :msg "request cache hit")
            (-> context
                (assoc :response cached-response)
                terminate))))
      context)))

(defn store-processed-response [context]
  (when (running?)
    (log/debug :fn ::store-processed-response :msg "storing processed response")
    (rocksdb/rocks-put! cache-store 
                        (get-in context [:request :body])
                        (:response context)))
  context)

(defn response-cache-interceptor []
  {:name ::response-cache
   :enter check-for-cached-response
   :leave store-processed-response})

(def expire-response-cache-interceptor
  "Interceptor for expiring cached http responses."
  {:name ::expire-response-cache
   :enter (fn [event] 
            (when (running?)
              (>!! @expiration-notification-chan true)) 
            event)})
