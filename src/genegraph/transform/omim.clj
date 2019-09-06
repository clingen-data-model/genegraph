(ns genegraph.transform.omim
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [genegraph.transform.core :refer [transform-doc src-path]]))

(def gene-prefix "https://www.ncbi.nlm.nih.gov/gene/")
(def mim-prefix "http://purl.obolibrary.org/obo/OMIM_")

(defn genemap2-row-to-triple [row]
  (let [ncbi-gene-id (nth row 9)
        ncbi-gene (str gene-prefix ncbi-gene-id)
        phenotype-mims (re-seq #"\d{4,}" (nth row 12))
        phenotypes (map #(str mim-prefix %) phenotype-mims)]
    (when (and (< 0 (count ncbi-gene-id)) (< 0 (count phenotypes)))
      (concat
       (map #(vector % :sepio/is-about-gene ncbi-gene) phenotypes)))))

(defn gene-topic-map [triples]
  (reduce (fn [acc [s _ o]] (assoc acc s (conj (acc s []) o))) {} triples))

(defn select-genetic-conditions [gene-subjects]
  (filter (fn [[_ v]] (= 1 (count v))) gene-subjects))

(defn construct-genetic-condition-triples [triples]
  (->> triples
       (mapcat (fn [[k v]] 
                 (when-let [condition (q/ld1-> (q/resource k) [[:owl/equivalent-class :<]])]
                   [[condition :rdf/type :sepio/GeneticCondition]
                    [condition :sepio/is-about-gene (q/resource (first v))]])))
       (remove nil?)))

(defn transform-genemap2 [genemap2]
  (let [genemap2-table (nthrest (csv/read-csv genemap2 :separator \tab) 4)]
    (->> genemap2-table
         (filter #(<= 13 (count %)))
         (mapcat genemap2-row-to-triple)
         (remove nil?)
         gene-topic-map
         select-genetic-conditions
         construct-genetic-condition-triples
         l/statements-to-model)))

(defmethod transform-doc :omim-genemap
  ([doc-def] (transform-doc doc-def (slurp (src-path doc-def))))
  ([doc-def doc] (transform-genemap2 doc)))
