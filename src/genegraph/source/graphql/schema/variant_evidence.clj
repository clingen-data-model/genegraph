(ns genegraph.source.graphql.schema.variant-evidence
  (:require [genegraph.database.query :as q]))

(def variant-evidence
  {:name :VariantEvidence
   :graphql-type :object
   :description "A description of a variant present in a proband used as evidence for an assertion or evidence line."
   :implements [:Resource]
   :fields {:variant {:type :VariationDescriptor
                      :description "Variation descriptor for this evidence"
                      :path [:sepio/is-about-allele]}
            :zygosity {:type :Resource
                       :description "Zygosity of this allele in the patient genome"
                       :path [:geno/has-zygosity]}
            :variant_type {:type 'String
                           :description "The variant type of this variant"
                           :path [:rdf/type]}
            :allele_origin {:type 'String
                            :description "Variant allele origin."
                            :path [:geno/allele-origin]}
            :paternity_maternity_confirmed {:type 'String
                                            :description "Paternity/maternity confirmed."
                                            :path [:sepio/paternity-maternity-confirmed]}
            :proband {:type :ProbandEvidence
                      :description "Proband in which this variant is present"
                      :path [[:sepio/has-variant :<]]}}})
