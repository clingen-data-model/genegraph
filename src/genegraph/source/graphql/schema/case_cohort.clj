(ns genegraph.source.graphql.schema.case-cohort
  (:require [genegraph.database.query :as q]))

(def case-cohort
  {:name :CaseCohort
   :graphql-type :object
   :description "A case cohort in a case control study."
   :implements [:Resource :Cohort]
   :fields { :disease {:type :Resource
                       :description "Mondo related disease in case cohort."
                       :path [:geno/related-condition]}}})

