(ns genegraph.transform.clinvar.hgvs
  (:require [clojure.pprint :refer [pprint]]))

(use 'clojure.repl)



(def sequence-info-re #"(.+)?:(.+)?\.(.*)")

; pattern for everything after the c./g./etc
(def coord-ranges-re #"\([\d_\?]+_[\d_\?]+\)_\([\d_\?]+_[\d_\?]+\)")
(def coord-range-re #"\(([\d_\?]+)_([\d_\?]+)\)")

(def expressions
  ["NC_000003.12:g.177772523_185716872dup"
   "NC_000017.10:g.(?_34508117)_(36248918_?)dup"
   "NC_000021.7:g.(40550036_40589822)_(46915388_46944323)del"])

(pprint
 (for [input-expresion expressions]
   (let [[matching-substring
          accession
          sequence-type
          remainder]
         (re-find (re-pattern sequence-info-re) input-expresion)]
     (-> {:matching-substring matching-substring
          :accession accession
          :sequence-type sequence-type
          :remainder remainder}
         (#(if-let [start-range (re-find (re-pattern (str "^" coord-range-re "_" "(.+)")) (:remainder %))]
             (assoc %
                    :start (-> start-range rest drop-last)
                    :remainder (last start-range))
             (assoc % :start (second (re-find #"(\d+)_" remainder)))))
         (#(if-let [end-range (re-find (re-pattern (str coord-range-re)) (:remainder %))]
             (assoc % :end (rest end-range))
             %))))))
