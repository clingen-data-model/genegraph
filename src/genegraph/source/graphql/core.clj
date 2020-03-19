(ns genegraph.source.graphql.core
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.gene :as gene]
            [genegraph.source.graphql.resource :as resource]
            [genegraph.source.graphql.actionability :as actionability]
            [genegraph.source.graphql.gene-validity :as gene-validity]
            [genegraph.source.graphql.gene-dosage :as gene-dosage]
            [genegraph.source.graphql.condition :as condition]
            [genegraph.source.graphql.server-status :as server-status]
            [genegraph.source.graphql.evidence :as evidence]
            [genegraph.source.graphql.value-set :as value-set]
            [genegraph.source.graphql.class :as rdf-class]
            [genegraph.source.graphql.property :as property]
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
    {:description "A gene. Along with conditions, one of the basic units of curation."
     :implements [:resource]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI representing the gene. Uses NCBI gene identifiers"}
              :label {:type 'String
                      :resolve resource/label
                      :description "Gene symbol"}
              :chromosome_band {:type 'String
                                 :resolve gene/chromosome-band
                                 :description "Cytogenetic band of gene."}
              :hgnc_id {:type 'String  
                        :resolve gene/hgnc-id
                        :description "HGNC ID of gene"}
              :curations {:type '(list :curation) :resolve gene/curations}
              :conditions {:type '(list :condition)
                           :resolve gene/conditions
                           :description "Genetic conditions associated with gene. This field is most frequently used for accessing actionability curations linked to a gene."}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve gene/actionability-curations
                                        :description "Actionability curations linked to a gene. Prefer using conditions.actionability_curations for most use cases, as this makes visible the gene-disease pairing used in the context of the curation."}
              :dosage_curations {:type '(list :gene_dosage_curation)
                                 :resolve gene/dosage-curations
                                 :description "Gene Dosage curations associated with the gene."}
              :validity_curations {:type '(list :gene_validity_curation)
                                   :resolve gene/validity-curations
                                   :description "Gene Validity curations associated with the gene."}}}

    :condition
    {:description "A disease or condition. May be a genetic condition, linked to a specific disease or mode of inheritance. Along with gene, one of the basic units of curation."
     :implements [:resource]
     :fields {:iri {:type 'String
                    :resolve condition/iri
                    :description "IRI for the condition. Currently MONDO ids are supported."}
              :label {:type 'String
                      :resolve condition/label
                      :description "Label for the condition."}
              :gene {:type :gene
                     :resolve condition/gene
                     :description "If the condiiton is a genetic condition, the gene associated with the condition."}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve condition/actionability-curations
                                        :description "Actionability curations associated with the condition"}
              :genetic_conditions {:type '(list :condition)
                                   :resolve condition/genetic_conditions
                                   :description "Genetic conditions that are direct subclasses of this condition."}}}

    :evidence
    {:description "An evidence item, typically used in support of an assertion made as a part of a curation"
     :fields {:source {:type 'String
                       :resolve evidence/source
                       :description "Origin of the evidence, i.e. PubMed reference"}
              :description {:type 'String
                            :resolve evidence/description
                            :description "Description of the evidence being cited."}}}


    :gene_dosage_curation
    {
     :implements [:resource :curation]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "Identifier for the curation"}
              :label {:type 'String
                      :resolve gene-dosage/label
                      :description "Curation label"}
              :wg_label {:type 'String
                         :resolve gene-dosage/wg-label
                         :description "Label for the working group responsible for the curation"}
              :classification_description {:type 'String 
                                           :resolve gene-dosage/classification-description
                                           :description "Summary of the classification and the rationale behind it"}
              :report_date {:type 'String
                            :resolve gene-dosage/report-date
                            :description "Date the report was last issued by the working group."}
              :evidence {:type '(list :evidence)
                         :resolve gene-dosage/evidence
                         :description "Evidence relating to the gene dosage curation."}}}
    
    :actionability_curation
    {:implements [:resource :curation]
     :fields 
     {:iri {:type 'String
            :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :report_date {:type 'String :resolve actionability/report-date}
      :wg_label {:type 'String :resolve actionability/wg-label}
      :report_id {:type 'String :resolve actionability/report-id}
      :classification_description {:type 'String :resolve actionability/classification-description}
      :conditions {:type '(list :condition)
                   :resolve actionability/conditions}
      :source {:type 'String :resolve actionability/source}}}

    :gene_validity_curation
    {:implements [:resource :curation]
     :fields
     {:iri {:type 'String
            :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :report_date {:type 'String :resolve gene-validity/report-date}
      :wg_label {:type 'String :resolve gene-validity/wg-label}
      :classification_description {:type 'String 
                                   :resolve gene-validity/classification-description}}}

    :server_status
    {:fields
     {:migration_version {:type 'String
                          :resolve server-status/migration-version}}}

    :concept
    {:implements [:resource]
     :fields
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :definition {:type 'String :resolve rdf-class/definition}}}


    :property
    {:implements [:resource]
     :fields
     {:iri {:type 'String :resolve property/iri}
      :label {:type 'String :resolve property/label}
      :definition {:type 'String :resolve property/definition}
      :min {:type 'Int :resolve property/min-count}
      :max {:type 'Int :resolve property/max-count}
      :display_arity {:type 'String :resolve property/display-arity}}}

    :class
    {:implements [:resource]
     :fields
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :definition {:type 'String :resolve rdf-class/definition}
      :properties {:type '(list :property) :resolve rdf-class/properties}
      :subclasses {:type '(list :class) :resolve rdf-class/subclasses}
      :superclasses {:type '(list :class) :resolve rdf-class/superclasses}}}

    :value_set
    {:implements [:resource]
     :fields
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :definition {:type 'String :resolve rdf-class/definition}
      :concepts {:type '(list :concept) :resolve value-set/concepts}}}}

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
    :value_sets {:type '(list :value_set)
                 :resolve value-set/value-sets-query}
    :model_classes {:type '(list :class)
                    :resolve rdf-class/model-classes-query}
    :server_status {:type '(non-null :server_status)
             :resolve server-status/server-version-query}}})

(defn schema []
  (schema/compile base-schema))

(defn gql-query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  [query-str]
  (lacinia/execute (schema) query-str nil nil))

