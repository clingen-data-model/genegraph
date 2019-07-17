(ns clingen-search.source.graphql.core
  (:require [clingen-search.database.query :as q]
            [clingen-search.source.graphql.gene :as gene]
            [clingen-search.source.graphql.resource :as resource]
            [clingen-search.source.graphql.actionability :as actionability]
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
              :curations {:type '(list :curation) :resolve gene/curations}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve gene/actionability-curations}}}
    :actionability_curation
    {:implements [:resource :curation]
     :fields 
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :report_date {:type 'String :resolve actionability/report-date}
      :wg_label {:type 'String :resolve actionability/wg-label}
      :classification_description {:type 'String :resolve actionability/classification-description}}}}

   :queries
   {:gene {:type '(non-null :gene)
           :args {:iri {:type 'String}}
           :resolve gene/gene-query}
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

