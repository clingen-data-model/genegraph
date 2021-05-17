(ns genegraph.source.graphql.experimental-schema
  (:require [genegraph.model.resource :as model-resource]
            [genegraph.model.assertion :as model-assertion]
            [genegraph.model.types :as model-types]
            [genegraph.model.agent :as model-agent]
            [genegraph.model.contribution :as model-contribution]
            [genegraph.model.find :as model-find]
            [com.walmartlabs.lacinia :as lacinia]
            [genegraph.database.util :refer [tx]]
            [genegraph.source.graphql.common.schema :as schema-builder]))

(def model
  [model-types/rdf-to-graphql-type-mappings
   model-resource/resource-interface
   model-resource/generic-resource
   model-resource/resource-query
   model-assertion/assertion
   model-contribution/contribution
   model-agent/agent
   model-find/types-enum
   model-find/find-query
   model-find/query-result
   model-find/find-query])


(defn schema []
  (schema-builder/schema model))

(defn schema-description []
  (schema-builder/schema-description model))

(defn query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  ([query-str]
   (tx (lacinia/execute (schema) query-str nil nil)))
  ([query-str variables]
   (tx (lacinia/execute (schema) query-str variables nil))))
