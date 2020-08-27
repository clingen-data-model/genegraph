(ns genegraph.source.graphql.classification
  (:require [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.common.curation :as curation]))

(defresolver classifications [args value]
  (curation/classifications))
