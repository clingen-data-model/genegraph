(ns genegraph.source.graphql.evidence
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(defresolver source [args value]
  (str (q/ld1-> value [:dc/source])))

(defresolver description [args value]
  (q/ld1-> value [:dc/description]))
