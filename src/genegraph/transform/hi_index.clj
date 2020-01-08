(ns genegraph.transform.hi-index
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.pedestal.log :as log]
            [genegraph.transform.core :refer [transform-doc src-path]]))

(def symbol-query "select ?s where { { ?s a :so/ProteinCodingGene . ?s :skos/preferred-label ?gene } union { ?s a :so/ProteinCodingGene . ?s :skos/hidden-label ?gene } }")

(defn hi-row-to-triple [row]
  (let [[gene-symbol _ hi-score] (str/split (nth row 3) #"\|")
        gene-uri (first (q/select symbol-query {:gene gene-symbol}))]
    (when (not (nil? gene-uri))
      (log/debug :fn :hi-row-to-triple :gene gene-symbol :msg "Triple created for row" :row row)
      (vector gene-uri :so/gain-of-function-variant hi-score))))

(defn transform-hi-scores [hi-records]
  (let [hi-table (nthrest (csv/read-csv hi-records :separator \tab) 1)]
    (->> hi-table
         (mapcat #(vector (hi-row-to-triple %)))
         (remove nil?)
         l/statements-to-model)))

(defmethod transform-doc :hi-index
  [doc-def]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (src-path doc-def)))]
    (transform-hi-scores (slurp in))))

