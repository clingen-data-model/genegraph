(ns genegraph.source.graphql.resource
  (:require [genegraph.database.query :as q]))

(defn iri [context args value]
  (str value))

(defn label [context args value]
  (first (concat (:skos/preferred-label value) (:rdfs/label value))))

(defn description [context args value]
  (first (:dc/description value)))
