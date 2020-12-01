(ns genegraph.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [genegraph.service :as service]
            [mount.core :as mount :refer [defstate]]
            [genegraph.sink.base :as base]
            [genegraph.sink.stream :as stream]
            [genegraph.migration :as migration]
            [genegraph.env :as env]
            [io.pedestal.log :as log]))


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

(defn start-server! []
  (let [service-map (case env/mode
                      "production" (service/service)
                      (service/dev-service))]
    (server/start 
     (server/create-server
      (merge-with into service-map status-routes)))))

(defstate server
  :start (start-server!)
  :stop (server/stop server))

(defn run-dev
  "Run a development-focused environment: skip connection to Kafka unless
  requested, watch for updates in base data."
  []
  (mount.core/start-without #'genegraph.sink.stream/consumer-thread)
  ;;(base/watch-base-dir)
  )

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (if (= 0 (count args))
    (do
      (log/info :fn :-main :message "Starting Genegraph")
      (mount.core/start #'server)
      (env/log-environment)
      (migration/populate-data-vol-if-needed)
      (mount.core/start)
      (reset! initialized? true))
    (do
      (log/info :fn :-main :message "Creating migration")
      (env/log-environment)
      (migration/create-migration))))



