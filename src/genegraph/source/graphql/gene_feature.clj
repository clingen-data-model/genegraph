(ns genegraph.source.graphql.gene-feature
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as str]))

(defresolver hgnc-id [args value]
  (q/ld1-> value [:owl/same-as]))

(defresolver hgnc-symbol [args value]
  (q/ld1-> value [:skos/preferred-label]))

(defresolver gene-type [args value]
  )

(defresolver locus-type [args value]
  )

(defresolver previous-symbols [args value]
  (str/join ", " (q/ld-> value [:skos/hidden-label])))

(defresolver alias-symbols [args value]
  (str/join ", " (q/ld-> value [:skos/alternate-label])))

(defresolver chromosomal-band [args value]
  (q/ld1-> value [:so/chromosome-band]))

(defresolver function [args value]
  )

(defresolver label [args value]
  (q/ld1-> value [:skos/preferred-label]))

(defresolver coordinates [args value]
  (q/ld-> value [:geno/has-location]))

