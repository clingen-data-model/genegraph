(ns genegraph.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [genegraph.service :as service]
            [mount.core :as mount :refer [defstate]]
            [genegraph.sink.base :as base]
            [genegraph.sink.stream :as stream]
            [genegraph.migration :refer [migrate!]]
            [genegraph.env :as env]))


(def initialized? (atom false))

(def status-routes 
  {::server/routes
   [["/live"
     :get (fn [_] {:status 200 :body "server is live"})
     :route-name ::liveness]
    ["/ready"
     :get (fn [_] (if (and @initialized? (stream/up-to-date?))
                    {:status 200 :body "server is ready"}
                    {:status 503 :body "server is not ready"}))
     :route-name ::readiness]]})

(defstate server
  :start (server/start 
          (server/create-server
           (merge-with into (service/service) status-routes)))
  :stop (server/stop server))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  ;; Start server first to support health check
  (mount.core/start #'server)
  (env/log-environment)
  (migrate!)
  ;; It's not possible to consume messages before the base state has been loaded
  ;; Make sure this happens first (synchronously)
  (mount.core/start-without #'genegraph.sink.stream/consumer-thread)
  (base/initialize-db!)
  (reset! initialized? true)
  (mount.core/start #'genegraph.sink.stream/consumer-thread))


