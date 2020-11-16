(ns genegraph.transform.hi-index
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.common-score :as com]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.pedestal.log :as log]
            [genegraph.transform.types :refer [transform-doc src-path]]))

(defn hi-row-to-triples [row]
  (let [[gene-symbol _ hi-score] (str/split (nth row 3) #"\|")
        gene-uri (first (q/select com/symbol-query {:gene gene-symbol}))
        import-date (com/date-time-now)]
    (when (not (nil? gene-uri))
      (log/debug :fn :hi-row-to-triples :gene gene-symbol :msg "Triples created for row" :row row)
      (com/common-row-to-triples gene-uri :cg/HaploinsufficiencyScore hi-score import-date "http://www.decipher.org"))))

(defn transform-hi-scores [hi-records]
  (let [hi-table (nthrest (csv/read-csv hi-records :separator \tab) 1)]
    (->> hi-table
         (mapcat hi-row-to-triples)
         (remove nil?)
         l/statements-to-model)))

(defmethod transform-doc :hi-index
  [doc-def]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (src-path doc-def)))]
    (transform-hi-scores (slurp in))))
