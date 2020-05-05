(ns genegraph.source.graphql.gene-feature
  (:require [genegraph.database.query :as q]
            [clojure.string :as str]))

(defn hgnc-id [context args value]
  (q/ld1-> value [:owl/same-as]))

(defn hgnc-symbol [context args value]
  (q/ld1-> value [:skos/preferred-label]))

(defn gene-type [context args value]
  )

(defn locus-type [context args value]
  )

(defn previous-symbols [context args value]
  (str/join ", " (q/ld-> value [:skos/hidden-label])))

(defn alias-symbols [context args value]
  (str/join ", " (q/ld-> value [:skos/alternate-label])))

(defn chromosomal-band [context args value]
  (q/ld1-> value [:so/chromosome-band]))

(defn function [context args value]
  )

(defn label [context args value]
  (q/ld1-> value [:skos/preferred-label]))

(defn coordinates [context args value]
  (q/ld-> value [:geno/has-location]))

