(ns genegraph.source.graphql.schema.case-control-evidence
  (:require [genegraph.database.query :as q]))

(def case-control-evidence
  {:name :CaseControlEvidence
   :graphql-type :object
   :description "Case control study evidence with a case cohort and a control cohort."
   :implements [:Resource]
   :fields {:case_cohort {:type :CaseCohort
                          :description "Case cohort."
                          :path [:sepio/has-case-cohort]}
            :control_cohort {:type :ControlCohort
                             :description "Cohort allele frequncy."
                             :path [:sepio/has-control-cohort]}
            :confidence_interval_from {:type 'Float
                                       :description "Confidence interval from value."
                                       :path [:stato/lower-confidence-limit]}
            :confidence_interval_to {:type 'Float
                                     :description "Confidence interval to value."
                                     :path [:stato/upper-confidence-limit]}
            :p_value {:type 'Float
                      :description "The p-value."
                      :path  [:obi/p-value]}
            :statistical_significance_type {:type 'String
                                           :description "Statistical significance type."
                                           :path [:sepio/statistical-significance-type]}
            :statistical_significance_value_type {:type 'String
                                                 :description "Statistical significance value type."
                                                 :path [:sepio/statistical-significance-value-type]}
            :statistical_significance_value {:type 'Float
                                           :description "Statistical significance value."
                                           :path [:sepio/statistical-significance-value]}}})
