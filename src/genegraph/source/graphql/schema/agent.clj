(ns genegraph.source.graphql.schema.agent
  (:require [genegraph.database.query :as q]))

(def agent
  {:name :Agent
   :graphql-type :object
   :description "An agent, either an individual or an organization."
   :implements [:Resource]
   :fields {:contributions {:type '(list :Contribution)
                            :description "Contributions to entities made by this agent"
                            :path [[:sepio/has-agent :<]]}}})


