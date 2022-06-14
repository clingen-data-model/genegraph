(ns genegraph.annotate.serialization
  (:require [genegraph.transform.types :as xform-types]
            [genegraph.source.graphql.experimental-schema :as experimental-schema]))


(def add-graphql-params-interceptor
  "Interceptor that adds graphql query information in key :graphql-params"
  {:name ::add-graphql-params-interceptor
   :enter (fn [event] (xform-types/add-event-graphql event))})

(def add-graphql-serialization-interceptor
  "Interceptor that executes graphql serialization and stores it in key :genegraph.annotate.serialization/graphql-serialization.
  Must be called "
  {:name ::add-graphql-serialization-interceptor
   :enter (fn [event]
            (let [gql-params (:graphql-params event)]
              (if (empty? gql-params)
                event
                (assoc event
                  ::graphql-serialization
                  (experimental-schema/query
                    (:query gql-params)
                    (:variables gql-params))))))})
