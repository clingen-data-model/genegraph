(ns genegraph.source.graphql.gene-feature
  (:require [genegraph.database.query :as q]))

(defn hgnc-id [context args value]
  )

(defn hgnc-symbol [context args value]
  )

(defn gene-type [context args value]
  )

(defn locus-type [context args value]
  )

(defn previous-symbols [context args value]
  )

(defn alias-symbols [context args value]
  )

(defn chromo-loc [context args value]
 )

(defn function [context args value]
  )

(defn label [context args value]
  (q/ld1-> value [:skos/preferred-label]))

(defn build [context args value]
  )

(defn chromosome [context args value]
  (q/ld1-> value [:skos/chromosome-band]))

(defn start-pos [context args value]
  )

(defn end-pos [context args value]
  )
