(ns genegraph.source.graphql.schema.proband-evidence
  (:require [genegraph.database.query :as q]))

(def proband-evidence
  {:name :ProbandEvidence
   :graphql-type :object
   :description "A description of a proband in the scientific literature or other source used as evidence for an assertion or evidence line."
   :implements [:Resource]
   :fields {:variants {:type '(list :VariationDescriptor)
                       :description "Alleles of interest in the genotype of this proband."
                       :path [:sepio/is-about-allele]}
            :variant_evidence {:type '(list :VariantEvidence)
                               :description "description of a variant present in the proband used as evidence for a curation"
                               :path [:sepio/has-variant]}
            :family {:type :Family
                     :description "Family proband is a member of."
                     :path [:sepio/is-about-family]}}})
