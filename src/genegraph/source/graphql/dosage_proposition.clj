(ns genegraph.source.graphql.dosage-proposition
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as str]
            [io.pedestal.log :as log]))

(defresolver wg-label [args value]
  "Gene Dosage Working Group")

(def evidence-level-enum 
{:sepio/DosageNoEvidence :NO_EVIDENCE
 :sepio/DosageMinimalEvidence :MINIMAL_EVIDENCE
 :sepio/DosageModerateEvidence :MODERATE_EVIDENCE
 :sepio/DosageSufficientEvidence :SUFFICIENT_EVIDENCE})

;; Reflects the classification from the dosage process and not the SEPIO-derived value
;; and not the SEPIO structure of gene dosage curations generally, pending a discussion
;; with stakeholders, hopefully can be reviewed and (perhaps) deprecated in future iterations
(defresolver ^:expire-by-value dosage-classification [args value]
  (if (q/is-rdf-type? value :sepio/EvidenceLevelAssertion)
    (case (q/to-ref (q/ld1-> value [:sepio/has-subject :sepio/has-predicate]))
      :geno/PathogenicForCondition (let [score (q/ld1-> value [:sepio/has-object])]
                                     {:label (q/ld1-> score [:rdfs/label])
                                      :ordinal (q/ld1-> score [:sepio/has-ordinal-position])
                                      :enum_value (-> score q/to-ref evidence-level-enum)})
      ;; For the moment, there is only a benign assertion if the score is 
      ;; 'dosage sensitivity unlikely
      :geno/BenignForCondition {:label "dosage sensitivity unlikely"
                                :ordinal 40
                                :enum_value :DOSAGE_SENSITIVITY_UNLIKELY})
    ;; If the assertion is of any other type, expect that its an assertion
    ;; the curation of the variant is outside the scope of curation
    {:label "gene associated with autosomal recessive phenotype"
     :ordinal 30
     :enum_value :ASSOCIATED_WITH_AUTOSOMAL_RECESSIVE_PHENOTYPE}))

(defresolver ^:expire-by-value classification-description [args value]
  (q/ld1-> value [:sepio/has-object :rdfs/label]))

(defresolver ^:expire-by-value report-date [args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(defresolver ^:expire-by-value evidence [args value]
  (q/ld-> value [:sepio/has-evidence]))

(defresolver ^:expire-by-value score [args value]
  (when-let [classification (classification-description nil args value)]
    (case (str/lower-case classification)
      "no evidence" :NO_EVIDENCE
      "minimal evidence" :MINIMAL_EVIDENCE
      "little evidence" :MINIMAL_EVIDENCE
      "moderate evidence" :MODERATE_EVIDENCE
      "sufficient evidence" :SUFFICIENT_EVIDENCE
      "gene associated with autosomal recessive phenotype" :ASSOCIATED_WITH_AUTOSOMAL_RECESSIVE_PHENOTYPE
      "dosage sensitivity unlikely" :DOSAGE_SENSITIVITY_UNLIKELY)))

(defresolver ^:expire-by-value assertion-type [args value]
  (if (= 1 (q/ld1-> value [:sepio/has-subject :sepio/has-subject :geno/has-member-count]))
    :HAPLOINSUFFICIENCY_ASSERTION
    :TRIPLOSENSITIVITY_ASSERTION))

(defresolver ^:expire-by-value comments [args value]
  (q/ld1-> value [:dc/description]))

(defresolver ^:expire-by-value phenotypes [args value]
  (str/join ", " (q/ld-> value [[:sepio/has-object :>] [:owl/equivalent-class :<] :rdfs/label])))

(defresolver ^:expire-by-value gene [args value]
  (q/ld1-> value [:sepio/has-subject :sepio/has-subject :geno/has-location]))

(defresolver ^:expire-by-value disease [args value]
  (let [disease  (q/ld1-> value [:sepio/has-subject :sepio/has-object])]
    (when-not (= (q/resource :mondo/Disease) disease)
      disease)))
