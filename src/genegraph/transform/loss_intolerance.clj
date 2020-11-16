(ns genegraph.transform.loss-intolerance
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.common-score :as com]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.pedestal.log :as log]
            [genegraph.transform.types :refer [transform-doc src-path]]))

;; TODO - try using the ensembl id (nth row 63) from the file to lookup using
;; "select ?s where { ?s a :so/ProteinCodingGene . ?s :owl/same-as ?ensembl }" {:ensembl "URI" }
;; (def ensembl-base "http://rdf.ebi.ac.uk/resource/ensembl/")
;; (def ensembl-query "select ?s where { ?s a :so/ProteinCodingGene . ?s :owl/same-as ?ensembl }")
;; (def ensembl-query "select ?s where { ?s :owl/same-as ?ensembl }")

(defn loss-row-to-triples [row]
  (let [gene-symbol (first row)
        ;; ensembl-uri (str ensembl-base (nth row 63))
        ;; gene-uri (first (q/select ensembl-query {:ensembl ensembl-uri}))
        gene-uri (first (q/select com/symbol-query {:gene gene-symbol}))
        loss-score (nth row 69)
        import-date (com/date-time-now)]
    (when (and (not (nil? gene-uri))
               (not (= "NA" loss-score)))
      (log/debug :fn :loss-row-to-triples :gene gene-symbol :msg "Triple created for row" :row row)
      (com/common-row-to-triples gene-uri :cg/TriplosensitivityScore loss-score import-date "http://www.gnomad.org"))))

(defn transform-loss-scores [loss-records]
  (let [loss-table (nthrest (csv/read-csv loss-records :separator \tab) 1)]
    (->> loss-table
         (mapcat loss-row-to-triples)
         (remove nil?)
         l/statements-to-model)))

(defmethod transform-doc :loss-intolerance
  [doc-def]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (src-path doc-def)))]
    (transform-loss-scores (slurp in))))

