(ns genegraph.model.proband-evidence
  (:require [genegraph.database.query :as q]))

(def proband-evidence
  {:name :ProbandEvidence
   :graphql-type :object
   :description "A description of a proband in the scientific literature or other source used as evidence for an assertion or evidence line."
   :implements [:Resource]
   :fields {:variants {:type '(list :VariationDescriptor)
                       :description "Alleles of interest in the genotype of this proband."
                       :path [:sepio/is-about-allele]}}})
