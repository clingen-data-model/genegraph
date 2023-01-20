(ns build
  "Build this thing."
  (:require [clojure.tools.build.api :as b]))

(def defaults
  "The defaults to configure a build."
  {:class-dir  "target/classes"
   :java-opts  ["-Dclojure.main.report=stderr"
                "--add-modules" "jdk.incubator.foreign"
                "--enable-native-access=ALL-UNNAMED"]
   :main       'genegraph.main
   :path       "target"
   :project    "deps.edn"
   :target-dir "target/classes"
   :uber-file  "target/genegraph.jar"})

(defn uber
  "Throw or make an uberjar from source."
  [_]
  (let [{:keys [paths] :as basis} (b/create-basis defaults)
        project                   (assoc defaults :basis basis)]
    (b/delete      project)
    (b/copy-dir    (assoc project :src-dirs paths))
    (b/compile-clj (assoc project
                          :src-dirs ["src"]
                          :ns-compile ['genegraph.main]))
    (b/uber        project)))

(defn uber-repl [_]
  ;; https://clojure.github.io/tools.build/clojure.tools.build.api.html#var-compile-clj
  (let [{:keys [paths] :as basis} (b/create-basis (assoc defaults :aliases [:with-nrepl-deps]))
        project                   (assoc defaults
                                         :basis basis
                                         :ns-compile ['genegraph.main-repl]
                                         :main 'genegraph.main-repl)]
    (b/delete      project)
    (b/copy-dir    (assoc project :src-dirs paths))   ;; include all resource dirs in target/
    (b/compile-clj (assoc project :src-dirs ["src"])) ;; but only compile src/
    (b/uber        project)))
