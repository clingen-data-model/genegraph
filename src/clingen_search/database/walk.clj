(ns clingen-search.database.walk
  (:require [clingen-search.database.names :as names]
            [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [tx]]
            [clojure.set :as set])
  (:import [org.apache.jena.rdf.model Property Literal Resource ResourceFactory
            Statement]))

;; TODO walk only traverses outward edges--need to specify and add syntax for
;; traversing in edges [:namespace/property :>]

;; default, outward facing edge :namespace/property
;; 


;; TODO cover case where local property name not found.

(defprotocol Steppable
  (step [edge start model]))

(extend-protocol Steppable

  ;; Single keyword, treat as [:ns/prop :>] (outward pointing edge)
  clojure.lang.Keyword
  (step [edge start model]
    (step [edge :>] start model))
  ;; (step [edge start model]
  ;;   (let [property (names/local-property-names edge)
  ;;         starting-resources (filter #(instance? Resource %) start)
  ;;         statements-per-node (map #(-> model (.listStatements % property nil) iterator-seq)
  ;;                                  starting-resources)]
  ;;     (into #{} (mapcat (fn [stmts] (map #(.getObject %) stmts)) 
  ;;                       statements-per-node))))p
  
  ;; Expect edge to be a vector with form [:ns/prop <direction>], where direction is one
  ;; of :> :< :-
  clojure.lang.IPersistentVector
  (step [edge start model]
    (let [property (names/local-property-names (first edge))
          out-fn (fn [n] (->> (.listStatements model n property nil) iterator-seq (map #(.getObject %))))
          in-fn (fn [n] (->> (.listStatements model nil property n) iterator-seq (map #(.getSubject %))))
          both-fn #(concat (out-fn %) (in-fn %))
          step-fn (case (second edge)
                    :> out-fn
                    :< in-fn
                    :- both-fn)
          starting-resources (filter #(instance? Resource %) start)
          statements-per-node (map step-fn starting-resources)]
      (into #{} (mapcat step-fn starting-resources))))`)

(defn walk [start-nodes & edges]
  (tx
   (let [model (.getDefaultModel db)
         results (reduce #(step %2 %1 model) start-nodes edges)]
     ;; TODO handle cases where literal result is something other than a string
     (mapv #(if (instance? Literal %) (.toString %) %) results))))
