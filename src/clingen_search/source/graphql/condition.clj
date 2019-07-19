(ns clingen-search.source.graphql.condition
  (:require [clingen-search.database.query :as q]))

(defn condition-query [context args value]
  (q/resource (:iri args)))

(defn actionability-curations [context args value]
  (q/ld-> value [[:sepio/is-about-condition :<]]))
