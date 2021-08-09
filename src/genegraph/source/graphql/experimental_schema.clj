(ns genegraph.source.graphql.experimental-schema
  (:require [genegraph.source.graphql.schema.resource :as model-resource]
            [genegraph.source.graphql.schema.statement :as model-statement]
            [genegraph.source.graphql.schema.evidence-line :as model-evidence-line]
            [genegraph.source.graphql.schema.types :as model-types]
            [genegraph.source.graphql.schema.agent :as model-agent]
            [genegraph.source.graphql.schema.contribution :as model-contribution]
            [genegraph.source.graphql.schema.find :as model-find]
            [genegraph.source.graphql.schema.proband-evidence :as model-proband]
            [genegraph.source.graphql.schema.variation-descriptor :as model-variation]
            [genegraph.source.graphql.schema.bibliographic-resource :as model-bibliographic-resource]
            [com.walmartlabs.lacinia :as lacinia]
            [genegraph.database.util :refer [tx]]
            [genegraph.source.graphql.common.schema :as schema-builder]))

(def model
  [model-types/rdf-to-graphql-type-mappings
   model-resource/resource-interface
   model-resource/generic-resource
   model-resource/resource-query
   model-statement/statement
   model-evidence-line/evidence-line
   model-contribution/contribution
   model-proband/proband-evidence
   model-variation/variation-descriptor
   model-agent/agent
   model-find/types-enum
   model-find/find-query
   model-find/query-result
   model-find/find-query
   model-bibliographic-resource/bibliographic-resource])


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
