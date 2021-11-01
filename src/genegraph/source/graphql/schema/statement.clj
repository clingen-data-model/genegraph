(ns genegraph.source.graphql.schema.statement
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.schema.common :as common]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(def statement
  {:name :Statement
   :graphql-type :object
   :description "An statement or proposition in the SEPIO model."
   :implements [:Resource]
   :fields {:subject {:type :Resource
                      :description "The subject of this statement"
                      :path [:sepio/has-subject]}
            :predicate {:type :Resource
                        :description "The predicate of this statement"
                        :path [:sepio/has-predicate]}
            :object {:type :Resource
                     :description "The object of this statement"
                     :path [:sepio/has-object]}
            :qualifier {:type '(list :Resource)
                        :description "Additional elements limiting the scope of the statement"
                        :path [:sepio/has-qualifier]}
            :score {:type 'Float
                    :description "Numeric score of the statement. May be nil, used only when the applicable criteria calls for a numeric score in the statement or critera assessment."
                    :path [:sepio/evidence-line-strength-score]}
            :contributions {:type '(list :Contribution)
                            :description "Contributions made by agents towards the creation of this resource."
                            :path [:sepio/qualified-contribution]}
            :evidence {:type '(list :Resource)
                       :description "Evidence used in in support of the statement"
                       :args {:class {:type 'String}
                              :transitive {:type 'Boolean}}
                       :resolve common/evidence-items}}})