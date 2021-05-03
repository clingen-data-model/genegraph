(ns genegraph.model.assertion
  (:require [genegraph.database.query :as q]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(def assertion
  {:name :Assertion
   :graphql-type :object
   :description "An assertion or proposition in the SEPIO model."
   :implements [:Resource]
   :fields {:subject {:type :Resource
                      :description "The subject of this assertion"
                      :path [:sepio/has-subject]}
            :predicate {:type :Resource
                        :description "The predicate of this assertion"
                        :path [:sepio/has-predicate]}
            :object {:type :Resource
                     :description "The object of this assertion"
                     :path [:sepio/has-object]}}})
