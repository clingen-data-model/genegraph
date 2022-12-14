(ns genegraph.main-repl
  (:require [genegraph.main]
            [mount.core :as mount]
            [nrepl.server])
  (:gen-class))

;; https://cljdoc.org/d/nrepl/nrepl/1.0.0/doc/usage/server#_embedding_nrepl

(mount/defstate nrepl-server
  :start (nrepl.server/start-server :port 6000)
  :stop (nrepl.server/stop-server nrepl-server))

(defn -main [& args]
  (mount/start #'nrepl-server)
  (apply genegraph.main/-main args))
