(ns genegraph.source.graphql.schema.contribution
  (:require [genegraph.database.query :as q]))

(def contribution
  {:name :Contribution
   :graphql-type :object
   :description "A contribution made by an agent to an entity."
   :fields {:attributed_to {:type :Agent
                            :description "The agent responsible for this contribution"
                            :path [:sepio/has-agent]}
            :date {:type 'String
                   :description "The date of this contribution"
                   :path [:sepio/activity-date]}
            :artifact {:type :Resource
                       :description "The artifact described in this contribution"
                       :path [[:sepio/qualified-contribution :<]]}}})
