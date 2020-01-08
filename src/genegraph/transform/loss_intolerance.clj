(ns genegraph.transform.loss-intolerance
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.pedestal.log :as log]
            [genegraph.transform.core :refer [transform-doc src-path]]))

;; TODO - try using the ensembl id (nth row 63) from the file to lookup using
;; "select ?s where { ?s a :so/ProteinCodingGene . ?s :owl/same-as ?ensembl }" {:ensembl "URI" }
;; if that doesn't work than consider refactoring this symbol lookup up to a common namespace
;; shared between hi_index.clj and loss_intolerance.clj
(def symbol-query "select ?s where { { ?s a :so/ProteinCodingGene . ?s :skos/preferred-label ?gene } union { ?s a :so/ProteinCodingGene . ?s :skos/hidden-label ?gene } }")

(defn loss-row-to-triple [row]
  (let [gene-symbol (first row)
        gene-uri (first (q/select symbol-query {:gene gene-symbol}))
        loss-score (nth row 69)]
    (when (not (nil? gene-uri))
      (log/debug :fn loss-row-to-triple :gene gene-symbol :msg "Triple created for row" :row row)
      (if (= "NA" loss-score)
        (vector gene-uri :so/loss-of-function-variant loss-score)
        (vector gene-uri :so/loss-of-function-variant (.toString (Double. loss-score)))))))

(defn transform-loss-scores [loss-records]
  (let [loss-table (nthrest (csv/read-csv loss-records :separator \tab) 1)]
    (->> loss-table
         (mapcat #(vector (loss-row-to-triple %)))
         (remove nil?)
         l/statements-to-model)))

(defmethod transform-doc :loss-intolerance
  [doc-def]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (src-path doc-def)))]
    (transform-loss-scores (slurp in))))

