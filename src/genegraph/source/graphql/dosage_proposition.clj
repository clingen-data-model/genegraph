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
      "no evidence" :NO_EVIDENCE
      "minimal evidence" :MINIMAL_EVIDENCE
      "moderate evidence" :MODERATE_EVIDENCE
      "sufficient evidence" :SUFFICIENT_EVIDENCE
      "gene associated with autosomal recessive phenotype" :ASSOCIATED_WITH_AUTOSOMAL_RECESSIVE_PHENOTYPE
      "dosage sensitivity unlikely" :DOSAGE_SENSITIVITY_UNLIKELY)))

(defn assertion-type [context args value]
  (if (= 1 (q/ld1-> value [:sepio/has-subject :sepio/has-subject :geno/has-member-count]))
    :HAPLOINSUFFICIENCY_ASSERTION
    :TRIPLOSENSITIVITY_ASSERTION))

(defn comments [context args value]
  (q/ld1-> value [:dc/description]))

(defn phenotypes [context args value]
  (str/join ", " (q/ld-> value [[:sepio/has-object :>] [:owl/equivalent-class :<] :rdfs/label])))

(defn gene [context args value]
  (q/ld1-> value [:sepio/has-subject :sepio/has-subject :geno/has-location]))

(defn disease [context args value]
  (q/ld1-> value [:sepio/has-subject :sepio/has-object [:owl/equivalent-class :<]]))
