(ns genegraph.model.types)

(def rdf-to-graphql-type-mappings
  {:type-mappings
   [[:sepio/Assertion :Assertion]
    [:sepio/Proposition :Assertion]]
   :default-type-mapping :GenericResource})

