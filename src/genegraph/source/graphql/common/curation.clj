(ns genegraph.source.graphql.common.curation
  (:require [genegraph.database.query :as q :refer [create-query]]))

(def gene-validity-bgp
  '[[validity_proposition :sepio/has-subject gene]
    [validity_proposition :sepio/has-object disease]
    [validity_proposition :rdf/type :sepio/GeneValidityProposition]])

(def actionability-bgp
  '[[actionability_genetic_condition :sepio/is-about-gene gene]
    [ac_report :sepio/is-about-condition actionability_genetic_condition]
    [ac_report :rdf/type :sepio/ActionabilityReport]
    [actionability_genetic_condition :rdfs/sub-class-of disease]])

(def gene-dosage-bgp
  '[[dosage_report :iao/is-about gene]
    [dosage_report :rdf/type :sepio/GeneDosageReport]])

(def gene-dosage-disease-bgp
  (conj gene-dosage-bgp
        '[dosage_report :bfo/has-part dosage_assertion]
        '[dosage_assertion :sepio/has-subject dosage_proposition]
        '[dosage_proposition :sepio/has-object omim_disease]
        '[disease :owl/equivalent-class omim_disease]
        ;; label filter included to remove non-mondo diseases
        ;; bit of a hack; should probably restructure dosage import to use mondo
        ;; condition instead of OMIM
        '[disease :rdfs/label disease_label]))

(def curation-bgps
  [gene-validity-bgp
   actionability-bgp
   gene-dosage-disease-bgp])

(def pattern-curation-activities
  [[gene-validity-bgp :GENE_VALIDITY]
   [actionability-bgp :ACTIONABILITY]
   ;; [gene-dosage-disease-bgp :GENE_DOSAGE] ;; omitting due to poor performance
   [gene-dosage-bgp :GENE_DOSAGE]])

(def test-resource-for-activity
  (map (fn [[pattern activity]]
         [(create-query (cons :bgp pattern) {::q/type :ask}) activity])
       pattern-curation-activities))

(defn activities [query-params]
  (reduce (fn [acc [test activity]] 
            (if (test query-params) 
              (conj acc activity)
              acc))
          #{}
          test-resource-for-activity))

(def union-of-all-curations
  (cons :union (map #(cons :bgp %) curation-bgps)))

(def actionability-curations-for-genetic-condition
  (create-query [:project ['ac_report]
                 (cons :bgp actionability-bgp)]))

(def gene-validity-curations
  (create-query [:project ['validity_assertion]
                 ;; Adding the reference to the assertion, plus any fields likely
                 ;; to be used as sort values
                 (cons :bgp 
                       (conj gene-validity-bgp
                             ['validity_assertion :sepio/has-subject 'validity_proposition]
                             ['gene :skos/preferred-label 'gene_label]
                             ['disease :rdfs/label 'disease_label]
                             ['validity_assertion :sepio/qualified-contribution 'gv_contrib]
                             ['gv_contrib :sepio/activity-date 'report_date]
                             ))]))

(def dosage-sensitivity-curations-for-genetic-condition
  (create-query [:project ['dosage_assertion]
                 (cons :bgp gene-dosage-disease-bgp)]))

(def curated-diseases-for-gene
  (create-query [:project ['disease]
                 union-of-all-curations]))

(defn curated-genetic-conditions-for-gene [query-params]
  (map #(array-map :gene (:gene query-params) :disease %) 
       (curated-diseases-for-gene query-params)))

