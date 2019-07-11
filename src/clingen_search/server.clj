(ns clingen-search.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [clingen-search.service :as service]
            [mount.core :as mount :refer [defstate]]
            [clingen-search.sink.base :as base]
            [clingen-search.sink.stream]))

(defstate server
  :start (server/start (server/create-server (service/service)))
  :stop (server/stop server))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (mount.core/start)
  (.start (Thread. base/initialize-db!)))


