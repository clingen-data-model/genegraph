(ns genegraph.sink.event
  (:require [genegraph.database.query :as q]
            [genegraph.database.load :refer [load-model]]
            [genegraph.annotate :as annotate :refer 
             [add-model add-iri add-metadata]]
            [genegraph.source.graphql.common.cache :as cache]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io PushbackReader File)))

(defn add-to-db! [event]
  (load-model (::q/model event) (::annotate/iri event))
  event)

(defn process-event! [event]
  (let [processed-event (-> event
                            add-metadata
                            add-model
                            add-iri
                            add-to-db!)]
    (cache/reset-cache!)
    processed-event))

(defn process-directory! 
  "Read and integrate a directory full of event records"
  [path]
  (let  [dir (File. path)
         files (filter #(re-find #".*\.edn$" (.getName %)) (file-seq dir))]
    (doseq [f files]
      (with-open [rdr (io/reader f)
                  pushback-rdr (PushbackReader. rdr)]
        (process-event! (edn/read pushback-rdr))))))
