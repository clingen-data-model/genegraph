(ns genegraph.source.graphql.dosage-proposition
  (:require [genegraph.database.query :as q]
            [clojure.string :as str]))

(defn wg-label [context args value]
  (println "DosageCuration - in WG-LABEL Value=" value)
  "Gene Dosage Working Group")

(defn classification-description [context args value]
  (q/ld1-> value [:sepio/has-object :rdfs/label]))

(defn report-date [context args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(defn evidence [context args value]
  (q/ld-> value [:sepio/has-evidence-line-with-item]))

(defn score [context args value]
  (when-let [classification (classification-description context args value)]
    (case (str/lower-case classification)
      "no evidence" 0
      "minimal evidence" 1
      "moderate evidence" 2
      "sufficient evidence" 3
      "gene associated with autosomal recessive phenotype" 30
      "dosage sensitivity unlikely" 40
      -1)))

(defn comments [context args value]
  (q/ld1-> value [:dc/description]))

(defn phenotypes [context args value]
  (str/join ", " (q/ld-> value [[:sepio/has-object :>] [:owl/equivalent-class :<] :rdfs/label])))
