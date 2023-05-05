(ns genegraph.main-repl
  (:require [genegraph.main]
            [genegraph.repl-server :as repl-server]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [nrepl.server])
  (:gen-class))

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
  (mount/start #'repl-server/nrepl-server)
  (if (= "repl-only" (first args))
    (while true (Thread/sleep (* 60 60 1000)))
    (apply genegraph.main/-main args)))
