(ns genegraph.source.graphql.schema.family
  (:require [genegraph.database.query :as q]))

(def family
  {:name :Family
   :graphql-type :object
   :description "A family in a genetic study. Will likely contain probands, may also include segregation evidence"
   :implements [:Resource]
   :fields {:probands {:type '(list :ProbandEvidence)
                       :description "Proband members of the family"
                       :path [:ro/has-member]}
            :segregation {:type :Segregation
                          :description "Segregation evidence for a family relative to a specific phenotype"
                          :path [[:sepio/is-about-family :<]]}
            :ethnicity {:type :Resource
                        :description "The familiy ethnicity"
                        :path [:sepio/ethnicity]}
            :mode_of_inheritance {:type 'String
                                  :description "Family mode of inheritence"
                                  :path [:sepio/has-mode-of-inheritance]}}})
