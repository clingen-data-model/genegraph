(ns genegraph.source.graphql.criteria
  (:require [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.common.curation :as curation]))

(defresolver criteria [args value]
  (curation/evaluation-criteria))
