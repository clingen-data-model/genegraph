(ns clingen-search.source.graphql.core
  (:require [clingen-search.database.query :as q]
            [clingen-search.source.graphql.gene :as gene]
            [clingen-search.source.graphql.resource :as resource]
            [clingen-search.source.graphql.actionability :as actionability]
            [clingen-search.source.graphql.condition :as condition]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]))

(def base-schema 
  {:interfaces
   {:resource
    {:fields {:iri {:type 'String}
              :label {:type 'String}}}
    :curation {:wg_label {:type 'String}
               :classification_description {:type 'String}
               :report_date {:type 'String}}}

   :objects
   {:gene
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve resource/label}
              :hgnc_id {:type 'String :resolve gene/hgnc-id}
              :curations {:type '(list :curation) :resolve gene/curations}
              :conditions {:type '(list :condition) :resolve gene/conditions}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve gene/actionability-curations}}}

    :condition
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve condition/iri}
              :label {:type 'String :resolve condition/label}
              :gene {:type :gene :resolve condition/gene}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve condition/actionability-curations}
              :genetic_conditions {:type '(list :condition)
                                   :resolve condition/genetic_conditions}}}

    :actionability_curation
    {:implements [:resource :curation]
     :fields 
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :report_date {:type 'String :resolve actionability/report-date}
      :wg_label {:type 'String :resolve actionability/wg-label}
      :classification_description {:type 'String :resolve actionability/classification-description}
      :conditions {:type '(list :condition)
                   :resolve actionability/conditions}
      :source {:type 'String :resolve actionability/source}}}}

   :queries
   {:gene {:type '(non-null :gene)
           :args {:iri {:type 'String}}
           :resolve gene/gene-query}
    :condition {:type '(non-null :condition)
                :args {:iri {:type 'String}}
                :resolve condition/condition-query}
    :actionability {:type '(non-null :actionability_curation)
                    :args {:iri {:type 'String}}
                    :resolve actionability/actionability-query}}})

(defn schema []
  (schema/compile base-schema))

(defn gql-query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  [query-str]
  (lacinia/execute (schema) query-str nil nil))

