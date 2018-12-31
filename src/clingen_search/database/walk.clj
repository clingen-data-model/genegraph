(ns clingen-search.database.walk
  (:require [clingen-search.database.names :as names]
            [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [tx]]
            [clojure.set :as set])
  (:import [org.apache.jena.rdf.model Property Literal Resource ResourceFactory
            Statement]))

;; TODO walk only traverses outward edges--need to specify and add syntax for
;; traversing in edges


(defn- step [model nodes edge]
  (let [property (names/local-property-names edge)
        resource-nodes (filter #(instance? Resource %) nodes)
        statements-per-node (map #(-> model (.listStatements % property nil) iterator-seq)
                                 resource-nodes)]
    (into #{} (mapcat (fn [stmts] (map #(.getObject %) stmts)) 
                      statements-per-node))))


(defn walk [start-nodes & edges]
  (tx
   (let [model (.getDefaultModel db)
         results (reduce #(step model %1 %2) start-nodes edges)]
     ;; TODO handle cases where literal result is something other than a string
     (mapv #(if (instance? Literal %) (.toString %) %) results))))
