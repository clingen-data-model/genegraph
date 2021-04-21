(ns genegraph.source.graphql.evidence-line
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(defresolver evidence-items [args value]
  (map #(tag-with-type % :GenericEvidenceItem) (:sepio/has-evidence-item value)))

(defresolver score [args value]
  (q/ld1-> value [:sepio/evidence-line-strength-score]))
