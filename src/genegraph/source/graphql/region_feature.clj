(ns genegraph.source.graphql.region-feature
  (:require [genegraph.database.query :as q]))

(defn label [context args value]
  (q/ld1-> value [:rdfs/label]))

(defn chromosomal-band [context args value]
  (q/ld1-> value [:so/chromosome-band]))

(defn coordinates [context args value]
  (q/ld-> value [:geno/has-location]))
