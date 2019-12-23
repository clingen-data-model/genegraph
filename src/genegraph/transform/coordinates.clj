(ns genegraph.transform.coordinates
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [genegraph.transform.core :refer [transform-doc src-path]]))

(def gene-prefix "https://www.ncbi.nlm.nih.gov/gene/")
(def nucore-prefix "https://www.ncbi.nlm.nih.gov/nuccore/")

(defn ncbi-row-to-features [row]
  (let [chromosome (nth row 5)
        assembly (nth row 6)
        assembly-uri (str nucore-prefix assembly)
        start (nth row 7)
        end (nth row 8)
        strand (nth row 9)
        ncbi-gene-symbol (nth row 14)
        ncbi-gene-id (nth row 15)
        ncbi-gene-uri (str gene-prefix ncbi-gene-id)]
    (vector chromosome assembly-uri start end strand ncbi-gene-uri)))

(defn features-to-triples [rows]
  (reduce (fn [triples row]
            (let [[build chromosome assembly-uri start end strand gene-uri] row
                  feature-blank (l/blank-node)
                  interval-blank (l/blank-node)]
              (conj triples
                    [(q/resource gene-uri) :geno/sequence-feature-set feature-blank]
                    [feature-blank :data/genome-build-identifier build]
                    [feature-blank :rdf/type :so/SequenceFeatureSet]
                    [feature-blank :so/chromosome chromosome]
                    [feature-blank :so/assembly (q/resource assembly-uri)]
                    [feature-blank :geno/on-strand strand]
                    [feature-blank :geno/has-interval interval-blank]
                    [interval-blank :rdf/type :geno/SequenceInterval]
                    [interval-blank :geno/start-position (Integer. (re-find #"[0-9]*" start))]
                    [interval-blank :geno/end-position (Integer. (re-find #"[0-9]*" end))])))
          []
          rows))

(defn transform-features [ncbi-features build]
  (let [ncbi-feature-table (csv/read-csv ncbi-features :separator \tab)]
    (->> ncbi-feature-table
         (filter #(= "gene" (first %)))
         (mapcat #(vector (cons build (ncbi-row-to-features %))))
         features-to-triples
         l/statements-to-model)))

(defmethod transform-doc :features
  [doc-def]
  (let [build (:build (:reader-opts doc-def))]
    (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (src-path doc-def)))]
      (transform-features (slurp in) build))))

