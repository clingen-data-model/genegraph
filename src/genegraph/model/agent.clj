(ns genegraph.model.agent
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.curation :as curation]))

(def agent
  {:name :Agent
   :graphql-type :object
   :description "An agent, either an individual or an organization."
   :implements [:Resource]
   :fields {:contributions {:type '(list :Contribution)
                            :description "Contributions to entities made by this agent"
                            :path [[:sepio/has-agent :<]]}}})


(def agents-query
  {:name :agents
   :graphql-type :query
   :description "A list of agents, definable by search parameter"
   :type '(list :Agent)
   :resolve (fn [_ args _]
              (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
                    query-params (if (:text args)
                                   {:text (-> args :text s/lower-case) ::q/params params}
                                   {::q/params params})
                    query (if (:text args)
                            affiliation-query-with-text
                            affiliation-query-without-text)]
                (query query-params)))})
