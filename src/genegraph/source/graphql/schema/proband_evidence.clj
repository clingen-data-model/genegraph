(ns genegraph.source.graphql.schema.proband-evidence
  (:require [genegraph.database.query :as q]))

(defn testing-methods [_ args value]
  "Produces a non-nil valued ordered vector of testing methods"
  (filterv some? (vector (q/ld1-> value [:sepio/first-testing-method])
                         (q/ld1-> value [:sepio/second-testing-method]))))

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
                     :path [:sepio/is-about-family]}
            :sex {:type :Resource
                  :description "The sex of the proband."
                  :path [:sepio/has-sex]}
            :age_unit {:type :Resource
                       :description "The unit of age measurement."
                       :path [:sepio/age-unit]}
            :age_type {:type :Resource
                       :description "The type of age measurement."
                       :path [:sepio/age-type]}
            :age_value {:type 'Int
                        :description "The age value of the proband."
                        :path [:sepio/age-value]}
            :previous_testing {:type 'Boolean
                               :description "Was there previous testing performed."
                               :path [:sepio/previous-testing]}
            :previous_testing_description {:type 'String
                                           :description "Description of the previous testing"
                                           :path [:sepio/previous-testing-description]}
            :testing_methods {:type '(list String) ;;'(list :Resource)
                              :description "Testing methods performed."
                              :resolve testing-methods}
            :phenotype_free_text {:type 'String
                                :description "Free text regarding the phenotypes."
                                :path [:sepio/has-textual-part]}
            :phenotypes {:type '(list :Resource)
                         :description "List of phenotypes."
                         :path [:sepio/is-about-condition]}
            :genotyping_method {:type 'String
                                :description "Genotyping method description"
                                :path [:sepio/detection-method]}
            :ethnicity {:type :Resource
                        :description "The ethnicity of the proband."
                        :path [:sepio/ethnicity]}
            :zygosity {:type :Resource
                        :description "The zygosity of the allele(s) on the proband."
                        :path [:geno/has-zygosity]}
            :paternity_maternity_confirmed {:type 'String
                                            :description "Paternity/maternity confirmed."
                                            :path [:sepio/paternity-maternity-confirmed]}}})
