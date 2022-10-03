(ns genegraph.source.graphql.schema.control-cohort
  (:require [genegraph.database.query :as q]))

(def control-cohort
  {:name :ControlCohort
   :graphql-type :object
   :description "A control cohort in a case control study."
   :implements [:Resource :Cohort]
   :fields {}})

