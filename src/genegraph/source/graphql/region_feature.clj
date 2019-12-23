(ns genegraph.source.graphql.region-feature
  (:require [genegraph.database.query :as q]))

(defn label [context args value]
  (q/ld1-> value [:rdfs/label]))

(defn build [context args value]
  )

(defn chromosome [context args value]
  (q/ld1-> value [:faldo/reference]))

(defn start-pos [context args value]
  (q/ld1-> value [:geno/start-position]))

(defn end-pos [context args value]
  (q/ld1-> value [:geno/end-position]))

(defn chromosome-band [context args value]
  (q/ld1-> value [:so/chromosome-band]))
