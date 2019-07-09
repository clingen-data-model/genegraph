(ns clingen-search.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [clingen-search.service :as service]
            [mount.core :as mount :refer [defstate]]
            [clojure.tools.cli :refer [parse-opts]]))

(defstate server
  :start (server/start (server/create-server (service/service)))
  :stop (server/stop server))

(def cli-options
  [["-p" "--port PORT" "Port number"]])

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (mount.core/start))


