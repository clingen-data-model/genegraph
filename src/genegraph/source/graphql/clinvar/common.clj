(ns genegraph.source.graphql.clinvar.common
  (:require [genegraph.database.names :refer [prefix-ns-map]]))

(defn cgterm
  [property-name]
  (str (prefix-ns-map "cgterms") property-name))
