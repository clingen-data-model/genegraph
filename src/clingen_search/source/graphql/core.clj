(ns clingen-search.source.graphql.core
  (:require [clingen-search.database.query :as q]
            [clingen-search.source.graphql.gene :as gene]
            [clingen-search.source.graphql.resource :as resource]
            [clingen-search.source.graphql.actionability :as actionability]
            [clingen-search.source.graphql.gene-dosage :as gene-dosage]
            [clingen-search.source.graphql.condition :as condition]
            [clingen-search.source.graphql.server-status :as server-status]
            [clingen-search.source.graphql.evidence :as evidence]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]))

(def base-schema 
  {:interfaces
   {:resource
    {:description "An RDF Resource; generic type suitable for return when a variety of resources may be returned as the result of a function all"
     :fields {:iri {:type 'String}
              :label {:type 'String}}}
    :curation 
    {:fields {:wg_label {:type 'String}
              :classification_description {:type 'String}
              :report_date {:type 'String}}}}

   :objects
   {:gene
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve resource/label}
              :hgnc_id {:type 'String :resolve gene/hgnc-id}
              :curations {:type '(list :curation) :resolve gene/curations}
              :conditions {:type '(list :condition) :resolve gene/conditions}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve gene/actionability-curations}
              :dosage_curations {:type '(list :gene_dosage_curation)
                                 :resolve gene/dosage-curations}}}

    :condition
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve condition/iri}
              :label {:type 'String :resolve condition/label}
              :gene {:type :gene :resolve condition/gene}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve condition/actionability-curations}
              :genetic_conditions {:type '(list :condition)
                                   :resolve condition/genetic_conditions}}}

    :evidence
    {:fields {:source {:type 'String :resolve evidence/source}
              :description {:type 'String :resolve evidence/description}}}

    :gene_dosage_curation
    {:implements [:resource :curation]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve gene-dosage/label}
              :wg_label {:type 'String :resolve gene-dosage/wg-label}
              :classification_description {:type 'String 
                                           :resolve gene-dosage/classification-description}
              :report_date {:type 'String :resolve gene-dosage/report-date}
              :evidence {:type '(list :evidence) :resolve gene-dosage/evidence}}}
    
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
      :source {:type 'String :resolve actionability/source}}}

    :server_status
    {:fields
     {:migration_version {:type 'String
                          :resolve server-status/migration-version}}}}

   :queries
   {:gene {:type '(non-null :gene)
           :args {:iri {:type 'String}}
           :resolve gene/gene-query}
    :condition {:type '(non-null :condition)
                :args {:iri {:type 'String}}
                :resolve condition/condition-query}
    :actionability {:type '(non-null :actionability_curation)
                    :args {:iri {:type 'String}}
                    :resolve actionability/actionability-query}
    :server_status {:type '(non-null :server_status)
             :resolve server-status/server-version-query}}})

(defn schema []
  (schema/compile base-schema))

(defn gql-query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  [query-str]
  (lacinia/execute (schema) query-str nil nil))

