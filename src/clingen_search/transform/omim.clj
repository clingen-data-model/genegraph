(ns clingen-search.transform.omim
  (:require [clingen-search.database.load :as l]
            [clingen-search.database.query :as q]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]))

(def gene-prefix "https://www.ncbi.nlm.nih.gov/gene/")
(def mim-prefix "http://purl.obolibrary.org/obo/OMIM_")

(defn genemap2-row-to-triple [row]
  (let [ncbi-gene-id (nth row 9)
        ncbi-gene (str gene-prefix ncbi-gene-id)
        phenotype-mims (re-seq #"\d{4,}" (nth row 12))
        phenotypes (map #(str mim-prefix %) phenotype-mims)]
    (when (and (< 0 (count ncbi-gene-id)) (< 0 (count phenotypes)))
      (map #(vector % :sepio/is-about-gene ncbi-gene) phenotypes))))

(defn transform-genemap2 [genemap2]
  (let [genemap2-table (nthrest (csv/read-csv genemap2 :separator \tab) 4)]
    (l/statements-to-model (remove nil? (mapcat genemap2-row-to-triple genemap2-table)))))
