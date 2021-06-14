(ns genegraph.source.graphql.contribution
  (:require [genegraph.database.query :as q :refer [ld-> ld1-> create-query resource]]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as s]))

(defresolver agent [args value]
  (ld1-> value [:sepio/has-agent]))

(defresolver realizes [args value]
  (ld1-> value [:bfo/realizes]))

(defresolver date [args value]
  (ld1-> value [:sepio/activity-date]))
