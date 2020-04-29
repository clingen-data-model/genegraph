(ns genegraph.source.graphql.resource
  (:require [genegraph.database.query :as q]))

(defn iri [context args value]
  (str value))

(defn label 
  "Presumption is that the first preferred label is the appropriate one to return."
  [context args value]
  (first (concat (:skos/preferred-label value) (:rdfs/label value))))

(defn alternative-label
  "Return the first :skos/alternative-label that exists. Does not map to any other 
  label definition"
  [context args value]
  (first (:skos/alternative-label value)))

(defn description [context args value]
  (first (:dc/description value)))
