(ns clingen-search.source.graphql.resource
  (:require [clingen-search.database.query :as q]))

(defn iri [context args value]
  (str value))

(defn label [context args value]
  (first (concat (:skos/preferred-label value) (:rdfs/label value))))
