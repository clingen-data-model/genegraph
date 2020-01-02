(ns genegraph.source.graphql.coordinate
  (:require [genegraph.database.query :as q]
            [clojure.string :as str]))

(defn build [context args value]
  (q/ld1-> value [:data/genome-build-identifier]))

(defn assembly [context args value]
  (q/ld1-> value [:so/assembly]))

(defn chromosome [context args value]
  (q/ld1-> value [:so/chromosome]))

(defn strand [context args value]
  (q/ld1-> value [:geno/on-strand]))

(defn start-pos [context args value]
  (q/ld1-> value [:geno/has-interval :geno/start-position]))

(defn end-pos [context args value]
  (q/ld1-> value [:geno/has-interval :geno/end-position]))
