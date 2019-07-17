(ns clingen-search.source.graphql.gene
  (:require [clingen-search.database.query :as q]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(defn gene-query [context args value]
  (q/resource (:iri args)))

(defn hgnc-id [context args value]
  (str (q/ld1-> value [:owl/same-as])))

(defn curations [context args value]
  (let [actionability (q/ld-> value [[:sepio/is-about-gene :<] [:sepio/is-about-condition :<]])]
    (map #(tag-with-type % :actionability_curation)) actionability))

(defn actionability-curations [context args value]
  (concat (q/ld-> value [[:sepio/is-about-gene :<] [:sepio/is-about-condition :<]])))
