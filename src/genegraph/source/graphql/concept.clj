(ns genegraph.source.graphql.concept
  (:require [genegraph.database.query :as q]))

(defn value-set [context args value]
  (q/ld1-> value [[:skos/is-in-scheme :<]]))


