(ns genegraph.util.repl
  "Functions and environment useful for developing Genegraph at the REPL"
  (:require [genegraph.database.query :as q]
            [genegraph.database.load :as l]
            [genegraph.database.instance :as db-instance]
            [genegraph.database.util :refer [tx]]
            [genegraph.sink.stream :as stream]
            [genegraph.sink.event :as event]
            [genegraph.annotate :as ann]
            [genegraph.rocksdb :as rocks]
            [genegraph.migration :as migrate]
            [cheshire.core :as json]
            [clojure.string :as s]))

(defn clear-named-grpahs-with-type [type-carrying-graph-name]
  (let [named-graphs (map str
                          (q/select "select ?graph where { ?graph a ?type } " 
                                    {:type type-carrying-graph-name}))]
    (doseq [graph-name named-graphs]
      (l/remove-model graph-name))))

(defn process-event-seq 
  "Run event sequence through event processor"
  [event-seq]
  (doseq [event event-seq]
    (event/process-event! event)))

(defn get-graph-names []
  (tx
   (into []
         (-> db-instance/db
             .listNames
             iterator-seq))))
