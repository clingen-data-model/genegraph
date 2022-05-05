(ns genegraph.server-repl
  (:gen-class) ; for -main method in uberjar
  (:require [genegraph.server :as server]
            [mount.core :as mount :refer [defstate]]
            [nrepl.core :as nrepl]
            [nrepl.server]))

(defstate
  repl-server
  :start (nrepl.server/start-server
           :init-ns 'genegraph.server
           :bind "127.0.0.1"
           :port 60001)
  :stop (nrepl.server/stop-server repl-server))

(defn -main [& args]
  (mount.core/start #'repl-server)
  (apply server/-main args))
