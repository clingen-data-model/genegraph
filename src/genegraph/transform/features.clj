(ns genegraph.transform.features
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [genegraph.transform.types :refer [transform-doc src-path]]))

(def gene-prefix "https://www.ncbi.nlm.nih.gov/gene/")
(def nucore-prefix "https://www.ncbi.nlm.nih.gov/nuccore/")

(defn ncbi-row-to-features [row]
  (let [assembly-uri (str nucore-prefix (nth row 6))
        start (nth row 7)
        end (nth row 8)
        strand (nth row 9)
        ncbi-gene-symbol (nth row 14)
        ncbi-gene-id (nth row 15)
        ncbi-gene-uri (str gene-prefix ncbi-gene-id)]
    (vector assembly-uri start end strand ncbi-gene-uri)))

(defn features-to-triples [rows]
  (reduce (fn [triples row]
            (let [[assembly-uri start end strand gene-uri] row
                  location-blank (l/blank-node)
                  interval-blank (l/blank-node)]
              (conj triples
                    [(q/resource gene-uri) :geno/has-location location-blank]
                    [location-blank :rdf/type :geno/SequenceFeatureLocation]
                    [location-blank :so/assembly (q/resource assembly-uri)] ; deprecated? =tristan
                    [location-blank
                     :geno/has-reference-sequence
                     (q/resource assembly-uri)]
                    [location-blank :geno/on-strand strand]
                    [location-blank :geno/has-interval interval-blank]
                    [interval-blank :rdf/type :geno/SequenceInterval]
                    [interval-blank :geno/start-position (Integer. (re-find #"[0-9]*" start))]
                    [interval-blank :geno/end-position (Integer. (re-find #"[0-9]*" end))])))
          []
          rows))

(defn transform-features [ncbi-features]
  (let [ncbi-feature-table (csv/read-csv ncbi-features :separator \tab)]
    (->> ncbi-feature-table
         (filter #(= "gene" (first %)))
         (mapcat #(vector (ncbi-row-to-features %)))
         features-to-triples
         l/statements-to-model)))

(defmethod transform-doc :features
  [doc-def]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (src-path doc-def)))]
    (transform-features (slurp in))))

