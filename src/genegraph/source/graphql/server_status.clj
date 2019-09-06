(ns genegraph.source.graphql.server-status
  (:require [genegraph.migration :as migration]))

(defn server-version-query [context args value]
  true)

(defn migration-version [context args value]
  (migration/current-version))
