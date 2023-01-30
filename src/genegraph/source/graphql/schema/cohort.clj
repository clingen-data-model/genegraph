(ns genegraph.source.graphql.schema.cohort
  (:require [genegraph.database.query :as q]))

(def cohort
  {:name :Cohort
   :graphql-type :interface
   :description "A case or control cohort in a case control study."
   :fields {:allele_frequency {:type 'Float
                               :description "Cohort allele frequncy."
                               :path [:sepio/allele-frequency]}
            :case_detection_method {:type 'String
                                    :description "Cohort case detection method."
                                    :path [:sepio/detection-method]}
            :all_genotyped_sequenced {:type 'Int
                                      :description "Cohort count of all genotyped sequenced."
                                      :path [:sepio/all-genotyped-sequenced]}
            :num_with_variant {:type 'Int
                               :description "Cohort number with variant."
                               :path  [:sepio/num-with-variant]}}})

