(ns genegraph.repl-server
  (:require [io.pedestal.log :as log]
            [mount.core :as mount]
            [nrepl.server :as nrepl-server]))

(def nrepl-port (System/getenv "GENEGRAPH_NREPL_PORT"))


;; https://cljdoc.org/d/nrepl/nrepl/1.0.0/doc/usage/server#_embedding_nrepl

(mount/defstate nrepl-server
  :start (when nrepl-port
           (log/info :msg "Starting nrepl server")
           (nrepl-server/start-server :port (-> "GENEGRAPH_NREPL_PORT"
                                                System/getenv
                                                parse-long)
                                    ;; Add host 127.0.0.1
                                      ))
  :stop (when nrepl-server
          (nrepl-server/stop-server nrepl-server)))
