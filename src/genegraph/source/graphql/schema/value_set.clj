(ns genegraph.source.graphql.schema.value-set
  (:require [genegraph.database.query :as q]))


(def value-set
  {:name :ValueSet
   :graphql-type :object
   :description "re-usable collections of terms that can be bound to attributes in a particular schema to constrain data entry."
   :implements [:Resource]
   :fields {:members {:type '(list :Resource)
                      :description "Concepts included in this value set."
                      :path [[:skos/is-in-scheme :<]]}}})
