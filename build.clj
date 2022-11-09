(ns build
  "Build this thing."
  (:require [clojure.pprint          :refer [pprint]]
            [clojure.tools.build.api :as b]))

(def root
  "Start here."
  {:class-dir  "target/classes"
   :main       'genegraph.server
   :path       "target"
   :project    "deps.edn"
   :target-dir "target/classes"
   :uber-file  "target/genegraph.jar"})

(defn uber
  "Make an uberjar from source."
  [_]
  (try (let [{:keys [paths] :as basis} (b/create-basis root)
             project                   (assoc root :basis basis)]
         (b/delete      project)
         (b/copy-dir    (assoc project :src-dirs paths))
         (b/compile-clj (assoc project :src-dirs ["src"]))
         (b/uber        project))
       (catch Exception x
         (pprint x)
         (throw x))))
