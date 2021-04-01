(ns genegraph.source.graphql.evidence-item
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(defresolver evidence-lines [args value]
  (:sepio/has-evidence-line value))
