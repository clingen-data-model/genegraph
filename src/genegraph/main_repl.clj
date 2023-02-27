(ns genegraph.main-repl
  (:require [genegraph.main]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [nrepl.server])
  (:gen-class))

;; https://cljdoc.org/d/nrepl/nrepl/1.0.0/doc/usage/server#_embedding_nrepl

(def nrepl-port (System/getenv "GENEGRAPH_NREPL_PORT"))

(mount/defstate nrepl-server
  :start (when nrepl-port
           (log/info :msg "Starting nrepl server")
           (nrepl.server/start-server :port (-> "GENEGRAPH_NREPL_PORT"
                                                System/getenv
                                                parse-long)
                                    ;; Add host 127.0.0.1
                                      ))
  :stop (when nrepl-server
          (nrepl.server/stop-server nrepl-server)))

(comment
  ; repl-only mode is enable just running a repl into this code or the compiled jar that does nothing
  (clojure.string/join " "
                       ["kubectl" "run"
                        "--image=gcr.io/clingen-dev/genegraph:vrs-cache"
                        "--image-pull-policy=Always"
                        "--env=GENEGRAPH_NREPL_PORT=6000"
                        "my-genegraph-repl"
                        "--" "java" "-jar" "-Xmx256m" "/app/app.jar" "nrepl-only"]))
(defn -main [& args]
  (log/info :args args)
  (mount/start #'nrepl-server)
  (if (= "repl-only" (first args))
    (while true (Thread/sleep (* 60 60 1000)))
    (apply genegraph.main/-main args)))
