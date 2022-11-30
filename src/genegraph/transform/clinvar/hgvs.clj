(ns genegraph.transform.clinvar.hgvs
  (:require [clojure.pprint :refer [pprint]]))

(def sequence-info-re #"(.+)?:(.+)?\.(.*)")

; pattern for everything after the c./g./etc
(def coord-ranges-re #"\([\d_\?]+_[\d_\?]+\)_\([\d_\?]+_[\d_\?]+\)")
(def coord-range-re #"\(([\d_\?]+)_([\d_\?]+)\)")


(defn hgvs-parse-sequence-and-location
  "Attempts to parse out HGVS terms from input-expression.
   {:accession The NC_... expression name
    :sequence-type g, c, m, etc
    :start Either an integer, a question mark, or a vector of two of either. 123, [123, ?], etc
    :end The same as :start
    :remainder Everything after the end location. A substitution, del, dup
    :input-expression The original expression passed in}"
  [input-expression]
  (letfn [(add-start [{:keys [remainder] :as %}]
            (if-let [start-range (re-find (re-pattern (str "^" coord-range-re "_" "(.+)")) remainder)]
              (assoc %
                     :start (-> start-range rest drop-last)
                     :remainder (last start-range))
              (assoc %
                     :start (nth (re-find #"(\d+)_" remainder) 1)
                     :remainder (nth (re-find #"(\d+)_(.*)" remainder) 2))))
          (add-end [{:keys [remainder] :as %}]
            (if-let [end-range (re-find (re-pattern (str coord-range-re "(.*)")) remainder)]
              (assoc %
                     :end (-> end-range rest drop-last)
                     :remainder (-> end-range last))
              (assoc %
                     :end (nth (re-find #"(\d+)" remainder) 1)
                     :remainder (nth (re-find #"(\d+)(.*)" remainder) 2))))]
    (let [[matching-substring
           accession
           sequence-type
           remainder]
          (re-find (re-pattern sequence-info-re) (or input-expression ""))]
      (some-> matching-substring
              (#(identity {:expression input-expression
                           :matching-substring %
                           :accession accession
                           :sequence-type sequence-type
                           :remainder remainder}))
              add-start
              add-end))))
