(ns genegraph.source.graphql.experimental-schema
  (:require [genegraph.model.resource :as model-resource]
            [genegraph.model.statement :as model-statement]
            [genegraph.model.evidence-line :as model-evidence-line]
            [genegraph.model.types :as model-types]
            [genegraph.model.agent :as model-agent]
            [genegraph.model.contribution :as model-contribution]
            [genegraph.model.find :as model-find]
            [genegraph.model.proband-evidence :as model-proband]
            [genegraph.model.variation-descriptor :as model-variation]
            [genegraph.model.bibliographic-resource :as model-bibliographic-resource]
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
