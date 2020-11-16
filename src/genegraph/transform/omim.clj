(ns genegraph.transform.omim
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [genegraph.transform.types :refer [transform-doc src-path]]))

(def gene-prefix "https://www.ncbi.nlm.nih.gov/gene/")
(def mim-prefix "http://purl.obolibrary.org/obo/OMIM_")

(defn phenotype-gene-map [triples]
  (reduce (fn [acc [phenotype _ gene]]
            (assoc acc phenotype (conj (acc phenotype []) gene)))
          {}
          triples))

(defn construct-genetic-condition-triples [pheno-genes-map]
  (->> pheno-genes-map
       (mapcat (fn [[pheno-iri gene-iris]] 
                 (reduce (fn [triples gene-iri]
                           (conj triples [(q/resource pheno-iri) :sepio/is-about-gene (q/resource gene-iri)]))
                         ;; initializes the triples vector to contain a triple
                         ;;     Mondo condition resource -> :rdf/type :sepio/GeneticCondition
                         ;; when there is only one gene associated to the phenotype
                         ;; else an empty vector
                         (if (= 1 (count gene-iris))
                           ;; If there is a MONDO equivalent to the OMIM
                           (if-let [mondo-condition (q/ld1-> (q/resource pheno-iri) [[:owl/equivalent-class :<]])]
                             [[mondo-condition :rdf/type :sepio/GeneticCondition]]
                             [])
                           [])
                         gene-iris)))
       (remove nil?)))

(defn genemap2-row-to-triple [row]
  (let [ncbi-gene-id (nth row 9)
        ncbi-gene (str gene-prefix ncbi-gene-id)
        phenotype-mims (re-seq #"\d{4,}" (nth row 12))
        phenotypes (map #(str mim-prefix %) phenotype-mims)]
    (when (and (< 0 (count ncbi-gene-id)) (< 0 (count phenotypes)))
      (concat
       (map #(vector % :sepio/is-about-gene ncbi-gene) phenotypes)))))

(defn transform-genemap2 [genemap2]
  (let [genemap2-table (nthrest (csv/read-csv genemap2 :separator \tab) 4)]
    (->> genemap2-table
         (filter #(<= 13 (count %)))
         (mapcat genemap2-row-to-triple)
         (remove nil?)
         phenotype-gene-map
         construct-genetic-condition-triples
         l/statements-to-model)))

(defmethod transform-doc :omim-genemap
  ([doc-def] (transform-doc doc-def (slurp (src-path doc-def))))
  ([doc-def doc] (transform-genemap2 doc)))
