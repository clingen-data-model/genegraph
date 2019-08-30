(ns clingen-search.source.graphql.server-status
  (:require [clingen-search.migration :as migration]))

(defn server-version-query [context args value]
  true)

(defn migration-version [context args value]
  (migration/current-version))
