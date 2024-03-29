(ns genegraph.source.graphql.experimental-schema
  (:require [genegraph.source.graphql.schema.resource :as model-resource]
            [genegraph.source.graphql.schema.statement :as model-statement]
            [genegraph.source.graphql.schema.evidence-line :as model-evidence-line]
            [genegraph.source.graphql.schema.types :as model-types]
            [genegraph.source.graphql.schema.agent :as model-agent]
            [genegraph.source.graphql.schema.contribution :as model-contribution]
            [genegraph.source.graphql.schema.find :as model-find]
            [genegraph.source.graphql.schema.proband-evidence :as model-proband]
            [genegraph.source.graphql.schema.variant-evidence :as model-variant-evidence]
            [genegraph.source.graphql.schema.family :as family]
            [genegraph.source.graphql.schema.variation-descriptor :as model-variation]
            [genegraph.source.graphql.schema.value-set :as value-set]
            [genegraph.source.graphql.schema.bibliographic-resource :as model-bibliographic-resource]
            [genegraph.source.graphql.schema.segregation :as model-segregation]
            [genegraph.source.graphql.schema.case-control-evidence :as model-case-control]
            [genegraph.source.graphql.schema.cohort :as model-cohort]
            [genegraph.source.graphql.schema.case-cohort :as model-case-cohort]
            [genegraph.source.graphql.schema.control-cohort :as model-control-cohort]
            [com.walmartlabs.lacinia :as lacinia]
            [genegraph.database.util :refer [tx]]
            [genegraph.source.graphql.common.schema :as schema-builder]
            [com.walmartlabs.lacinia.schema :as lacinia-schema]
            [genegraph.source.graphql.core :as legacy-schema]
            [medley.core :as medley]))

(def model
  [model-types/rdf-to-graphql-type-mappings
   model-resource/resource-interface
   model-resource/generic-resource
   model-resource/resource-query
   model-resource/record-metadata-query
   model-resource/record-metadata-query-result
   model-statement/statement
   model-evidence-line/evidence-line
   model-contribution/contribution
   model-proband/proband-evidence
   model-variant-evidence/variant-evidence
   model-variation/variation-descriptor
   model-variation/categorical-variation-descriptor
   model-variation/vrs-expression
   model-variation/vrs-variation-member
   model-variation/vrs-extension
   model-variation/vrs-allele
   model-variation/vrs-text
   model-variation/vrs-canonical-variation
   model-variation/vrs-absolute-copy-number
   model-variation/vrs-literal-sequence-expression
   model-variation/vrs-sequence-location
   model-variation/vrs-sequence-interval
   model-variation/vrs-number
   model-variation/variation-descriptor-query
   model-agent/agent
   model-find/types-enum
   model-find/find-query
   model-find/query-result
   model-find/find-query
   value-set/value-set
   family/family
   model-segregation/segregation
   model-case-control/case-control-evidence
   model-cohort/cohort
   model-case-cohort/case-cohort
   model-control-cohort/control-cohort
   model-bibliographic-resource/bibliographic-resource])


(defn schema []
  (schema-builder/schema model))

(defn merged-schema []
  (-> (legacy-schema/schema-for-merge)
      (medley/deep-merge (schema-builder/schema-description model))
      lacinia-schema/compile))

(defn schema-description []
  (schema-builder/schema-description model))

(defn query
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  ([query-str]
   (tx (lacinia/execute (schema) query-str nil nil)))
  ([query-str variables]
   (tx (lacinia/execute (schema) query-str variables nil))))
