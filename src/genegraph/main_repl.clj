(ns genegraph.main-repl
  (:require [genegraph.main]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [nrepl.server])
  (:gen-class))

;; https://cljdoc.org/d/nrepl/nrepl/1.0.0/doc/usage/server#_embedding_nrepl

(mount/defstate nrepl-server
  :start (nrepl.server/start-server :port (-> "GENEGRAPH_NREPL_PORT"
                                              System/getenv
                                              (or "6000")
                                              parse-long))
  :stop (nrepl.server/stop-server nrepl-server))

(defn -main [& args]
  (when (seq (System/getenv "GENEGRAPH_NREPL_PORT"))
    (log/info :msg "Starting nrepl server")
    (mount/start #'nrepl-server))
  (apply genegraph.main/-main args))
