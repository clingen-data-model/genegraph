(ns genegraph.source.graphql.coordinate
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as str]))

(defresolver build [args value]
  (q/ld1-> value [:so/assembly :data/genome-build-identifier]))

(defresolver assembly [args value]
  (q/ld1-> value [:so/assembly]))

(defresolver chromosome [args value]
  (q/ld1-> value [:so/assembly :so/chromosome]))

(defresolver strand [args value]
  (q/ld1-> value [:geno/on-strand]))

(defresolver start-pos [args value]
  (q/ld1-> value [:geno/has-interval :geno/start-position]))

(defresolver end-pos [args value]
  (q/ld1-> value [:geno/has-interval :geno/end-position]))
