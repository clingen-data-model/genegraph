(ns genegraph.source.graphql.value-set
  (:require [genegraph.database.query :as q]))

(defn value-sets-query [context args value]
  (q/select "select ?x where { ?x a :sepio/ValueSet }"))

(defn concepts [context args value]
  (get value [:skos/is-in-scheme :<]))
