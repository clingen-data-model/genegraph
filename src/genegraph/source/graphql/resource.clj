(ns genegraph.source.graphql.resource
  (:require [genegraph.database.query :as q]))

(defn iri [context args value]
  (str value))

(defn curie [context args value]
  (q/curie value))

(def label-memo 
  (memoize (fn [resource] (first (concat (:skos/preferred-label resource)
                                         (:rdfs/label resource))))))

(defn label 
  "Presumption is that the first preferred label is the appropriate one to return."
  [context args resource]
  (label-memo resource)
  ;;(first (concat (:skos/preferred-label resource) (:rdfs/label resource)))
  )

(def alternative-label-memo
  (memoize (fn [resource] (first (:skos/alternative-label resource)))))

(defn alternative-label
  "Return the first :skos/alternative-label that exists. Does not map to any other 
  label definition"
  [context args resource]
  (alternative-label-memo resource)
  ;;(first (:skos/alternative-label resource))
  )

(defn description [context args value]
  (first (:dc/description value)))
