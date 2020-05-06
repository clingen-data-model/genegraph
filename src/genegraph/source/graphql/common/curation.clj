(ns genegraph.source.graphql.common.curation
  (:require [genegraph.database.query :as q :refer [create-query]]))


(def gene-validity-bgp
  '[[validity_proposition :sepio/has-subject gene]
    [validity_proposition :sepio/has-object disease]
    [validity_proposition :rdf/type :sepio/GeneValidityProposition]])

(def actionability-bgp
  '[[actionability_genetic_condition :sepio/is-about-gene gene]
    [ac_proposition :sepio/is-about-condition actionability_genetic_condition]
    [ac_proposition :rdf/type :sepio/ActionabilityReport]
    [actionability_genetic_condition :owl/sub-class-of disease]])

(def gene-dosage-bgp
  '[[dosage_report :iao/is-about gene]
    [dosage_report :rdf/type :sepio/GeneDosageReport]])

(def gene-dosage-disease-bgp 
  '[[dosage_assertion :rdf/type :sepio/EvidenceLevelAssertion]
    [dosage_assertion :sepio/has-subject dosage_proposition]
    [dosage_proposition :sepio/has-subject copy_number]
    [dosage_proposition :sepio/has-object disease]
    [copy_number :geno/has-location gene]])


