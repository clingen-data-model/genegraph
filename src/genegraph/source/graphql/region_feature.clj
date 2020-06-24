(ns genegraph.source.graphql.region-feature
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(defresolver label [args value]
  (q/ld1-> value [:rdfs/label]))

(defresolver chromosomal-band [args value]
  (q/ld1-> value [:so/chromosome-band]))

(defresolver coordinates [args value]
  (q/ld-> value [:geno/has-location]))
