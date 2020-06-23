(ns genegraph.source.graphql.dosage-proposition
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as str]))

(defresolver wg-label [args value]
  (println "DosageCuration - in WG-LABEL Value=" value)
  "Gene Dosage Working Group")

(defresolver classification-description [args value]
  (q/ld1-> value [:sepio/has-object :rdfs/label]))

(defresolver report-date [args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(defresolver evidence [args value]
  (q/ld-> value [:sepio/has-evidence-line-with-item]))

(defresolver score [args value]
  (when-let [classification (classification-description args value)]
    (case (str/lower-case classification)
      "no evidence" :NO_EVIDENCE
      "minimal evidence" :MINIMAL_EVIDENCE
      "moderate evidence" :MODERATE_EVIDENCE
      "sufficient evidence" :SUFFICIENT_EVIDENCE
      "gene associated with autosomal recessive phenotype" :ASSOCIATED_WITH_AUTOSOMAL_RECESSIVE_PHENOTYPE
      "dosage sensitivity unlikely" :DOSAGE_SENSITIVITY_UNLIKELY)))

(defresolver assertion-type [args value]
  (if (= 1 (q/ld1-> value [:sepio/has-subject :sepio/has-subject :geno/has-member-count]))
    :HAPLOINSUFFICIENCY_ASSERTION
    :TRIPLOSENSITIVITY_ASSERTION))

(defresolver comments [args value]
  (q/ld1-> value [:dc/description]))

(defresolver phenotypes [args value]
  (str/join ", " (q/ld-> value [[:sepio/has-object :>] [:owl/equivalent-class :<] :rdfs/label])))

(defresolver gene [args value]
  (q/ld1-> value [:sepio/has-subject :sepio/has-subject :geno/has-location]))

(defresolver disease [args value]
  (q/ld1-> value [:sepio/has-subject :sepio/has-object [:owl/equivalent-class :<]]))
