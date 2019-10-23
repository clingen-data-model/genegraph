(ns genegraph.source.graphql.gene-facts
  (:require [genegraph.database.query :as q]))

(defn hgnc-symbol [context args value]
  (q/ld-> value []))

(defn hgnc-name [context args value]
  (q/ld-> value []))

(defn gene-type [context args value]
  (q/ld-> value []))

(defn locus-type [context args value]
  (q/ld-> value []))

(defn previous-symbols [context args value]
  (q/ld-> value []))

(defn alias-symbols [context args value]
  (q/ld-> value []))

(defn chromo-loc [context args value]
 (q/ld-> value []))

(defn function [context args value]
  (q/ld-> value []))

(defn coordinates [context args value]
  (q/ld-> value []))

(defn build [context args value]
  (q/ld-> value []))

(defn chromosome [context args value]
  (q/ld-> value []))

(defn start-pos [context args value]
  (q/ld-> value []))

(defn end-pos [context args value]
  (q/ld-> value []))
