(ns genegraph.source.graphql.coordinates
  (:require [genegraph.database.query :as q]))

(defn build [context args value]
  (q/ld-> value []))

(defn chromosome [context args value]
  (q/ld-> value []))

(defn start-pos [context args value]
  (q/ld-> value []))

(defn end-pos [context args value]
  (q/ld-> value []))
