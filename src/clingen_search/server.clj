(ns clingen-search.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [clingen-search.service :as service]
            [mount.core :as mount :refer [defstate]]
            [clingen-search.sink.base :as base]
            [clingen-search.sink.stream]
            [clingen-search.env :as env]))

(defstate server
  :start (server/start (server/create-server (service/service)))
  :stop (server/stop server))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  ;; It's not possible to consume messages before the base state has been loaded
  ;; Make sure this happens first (synchronously)
  (env/log-environment)
  (mount.core/start-without #'clingen-search.sink.stream/consumer-thread)
  (base/initialize-db!)
  (mount.core/start #'clingen-search.sink.stream/consumer-thread))


