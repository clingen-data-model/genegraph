(ns genegraph.source.graphql.schema.variant-evidence
  (:require [genegraph.database.query :as q]))

(def variant-evidence
  {:name :VariantEvidence
   :graphql-type :object
   :description "A description of a variant present in a proband used as evidence for an assertion or evidence line."
   :implements [:Resource]
   :fields {:variant {:type :VariationDescriptor
                      :description "Variation descriptor for this evidence"
                      :path [:sepio/is-about-allele]}}})
