(ns genegraph.source.graphql.core
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.gene :as gene]
            [genegraph.source.graphql.resource :as resource]
            [genegraph.source.graphql.actionability :as actionability]
            [genegraph.source.graphql.gene-validity :as gene-validity]
            [genegraph.source.graphql.gene-dosage :as gene-dosage]
            [genegraph.source.graphql.gene-feature :as gene-feature]
            [genegraph.source.graphql.region-feature :as region-feature]
            [genegraph.source.graphql.coordinate :as coordinate]
            [genegraph.source.graphql.dosage-proposition :as dosage-proposition]
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
  {:enums
   {:CurationActivity
    {:description "The curation activities within ClinGen. Each curation is associated with a curation activity."
     :values [:ALL :ACTIONABILITY :GENE_VALIDITY :GENE_DOSAGE]}}

   :interfaces
   {:resource
    {:description "An RDF Resource; generic type suitable for return when a variety of resources may be returned as the result of a function all"
     :fields {:iri {:type 'String}
              :label {:type 'String}}}

    :genomic_coordinate
    {:description "Genomic coordinate of a Gene or Region"
     :fields {:build {:type 'String
                      :description "The build name"}
              :assembly {:type 'String
                      :description "The assembly name"}
              :chromosome {:type 'String
                           :description "The chromosome name"}
              :start_pos {:type 'Int
                          :description "Start coordinate"}
              :end_pos {:type 'Int
                        :description "End coordinate"}
              :strand {:type 'String
                       :description "Strand the feature appears on."}}}
    
    :genomic_feature
    {:description "Genomic feature represented by a Gene or Region"
     :fields {:coordinates {:type '(list genomic_coordinate)}}}

    :curation 
    {:fields {:wg_label {:type 'String}
              :classification_description {:type 'String}
              :report_date {:type 'String}}}}

   :objects
   {:gene
    {:description "A genomic feature (a gene or a region). Along with conditions, one of the basic units of curation."
     :implements [:resource]
     :fields {:iri {:type 'String
                   :resolve resource/iri
                    :description "IRI representing the gene. Uses NCBI gene identifiers"}
              :label {:type 'String
                      :resolve resource/label
                      :description "Gene symbol"}
              :alternative_label {:type 'String
                                  :resolve resource/alternative-label
                                  :description "Full name of gene"}
              :chromosome_band {:type 'String
                                :resolve gene/chromosome-band
                                :description "Cytogenetic band of gene."}
              :hgnc_id {:type 'String  
                        :resolve gene/hgnc-id
                        :description "HGNC ID of gene"}
              :curation_activities {:type '(list :CurationActivity)
                                    :description "The curation activities that have published reports on the gene"
                                    :resolve gene/curation-activities}
              :curations {:type '(list :curation)
                          :resolve gene/curations}
              :conditions {:type '(list :condition)
                           :resolve gene/conditions
                           :description "Genetic conditions associated with gene. This field is most frequently used for accessing actionability curations linked to a gene."}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve gene/actionability-curations
                                        :description "Actionability curations linked to a gene. Prefer using conditions.actionability_curations for most use cases, as this makes visible the gene-disease pairing used in the context of the curation."}
              :dosage_curations {:type '(list :gene_dosage_curation)
                        :resolve gene/dosage-curations
                        :description "Gene Dosage curations associated with the gene or region."}}}

    :coordinate
    {:description "a genomic coordinate"
     :implements [:genomic_coordinate]
     :fields {:build {:type 'String
                      :resolve coordinate/build}
              :assembly {:type 'String
                      :resolve coordinate/assembly}
              :chromosome {:type 'String
                           :resolve coordinate/chromosome}
              :strand {:type 'String
                       :resolve coordinate/strand}
              :start_pos {:type 'Int
                          :resolve coordinate/start-pos}
              :end_pos {:type 'Int
                        :resolve coordinate/end-pos}}}
    
    :gene_feature
    {:description "A gene feature"
     :implements [:resource :genomic_feature]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI representing the gene. Uses NCBI gene identifiers"}
              :label {:type 'String
                      :resolve resource/label
                      :description "Gene symbol"}
              :hgnc_id {:type 'String
                        :resolve gene-feature/hgnc-id
                        :description "HGNC ID of gene"}
              :gene_type {:type 'String
                          :resolve gene-feature/gene-type
                          :description "The gene type"}
              :locus_type {:type 'String
                           :resolve gene-feature/locus-type
                           :description "The gene locus type"}
              :previous_symbols {:type 'String
                                 :resolve gene-feature/previous-symbols
                                 :description "The list of previous gene symbols"}
              :alias_symbols {:type 'String
                              :resolve gene-feature/alias-symbols
                              :description "The list of gene aliases"}
              :chromosomal_band {:type 'String
                           :resolve gene-feature/chromosomal-band
                           :description "The chromosomal location"}
              :function {:type 'String
                         :resolve gene-feature/function
                         :description "A description of the genes function"}
              :coordinates {:type '(list :coordinate)
                            :resolve gene-feature/coordinates
                            :description "Coordinates of the feature"}}}
    
    :region_feature
    {:description "A region feature"
     :implements [:resource :genomic_feature]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI representing the region"}
              :label {:type 'String
                      :resolve resource/label
                      :description "Region symbol"}
              :chromosomal_band {:type 'String
                                :resolve region-feature/chromosomal-band
                                :description "The chromosomal location"}
              :coordinates {:type '(list :coordinate)
                            :resolve region-feature/coordinates
                            :description "Coordinates of the feature"}}}

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
                                   :resolve condition/genetic-conditions
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
    {:description "A complete gene dosage curation containing one or more dosage propositions"
     :implements [:resource]
     :fields {:iri {:type 'String
                    :resolve resource/iri}
              :label {:type 'String
                      :resolve gene-dosage/label}
              :wg_label {:type 'String
                         :resolve gene-dosage/wg-label}
              :report_date {:type 'String
                            :resolve gene-dosage/report-date}
              :genomic_feature {:type :genomic_feature
                                :resolve gene-dosage/genomic-feature}
              :haplo {:type :dosage_proposition
                      :resolve gene-dosage/haplo
                      :description "Haploinsufficiency"}
              :triplo {:type :dosage_proposition
                       :resolve gene-dosage/triplo
                       :description "Triplosensitivity"}
              :haplo_index {:type 'String
                            :resolve gene-dosage/haplo-index
                            :description "Haploinsufficiency index percent"}
              :morbid {:type '(list String)
                       :resolve gene-dosage/morbid
                       :description "Gene morbidity OMIM numbers"}
              :morbid_phenotypes {:type '(list String)
                                  :resolve gene-dosage/morbid-phenotypes
                                  :description "Gene morbidity phenotypes"}
              :omim {:type 'Boolean
                     :resolve gene-dosage/omim}
              :pli_score {:type 'String
                          :resolve gene-dosage/pli-score
                          :description "Loss intolerence (pLI)"}
              :location_relationship {:type 'String
                                      :resolve gene-dosage/location-relationship
                                      :description "Location relationship"}}}

    :dosage_proposition
    {:description "An individual dosage proposition, either a Haplo Insufficiency proposition or a Triplo Sensitivity proposition"
     :implements [:resource :curation]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "Identifier for the curation"}
              :label {:type 'String
                      :resolve resource/label
                      :description "Curation label"}
              :wg_label {:type 'String
                         :resolve dosage-proposition/wg-label
                         :description "Label for the working group responsible for the curation"}
              :classification_description {:type 'String
                                           :resolve dosage-proposition/classification-description
                                           :description "Summary of the classification and the rationale behind it"}
              :report_date {:type 'String
                            :resolve dosage-proposition/report-date
                            :description "Date the report was last issued by the working group."}
              :evidence {:type '(list :evidence)
                         :resolve dosage-proposition/evidence
                         :description "Evidence relating to the gene dosage curation."}
              :score {:type 'Int
                      :resolve dosage-proposition/score
                      :description "Sufficiency score"}
              :phenotypes {:type 'String
                           :resolve dosage-proposition/phenotypes
                           :description "The phenotypes to which the evidence applies"}
              :comments {:type 'String
                         :resolve dosage-proposition/comments
                         :description "Comments related to this curation"}}}

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
      :concepts {:type '(list :concept) :resolve value-set/concepts}}}

    :totals
    {:fields
     {:total {:type 'Int :resolve gene-dosage/total-count}
      :genes  {:type 'Int :resolve gene-dosage/gene-count}
      :regions  {:type 'Int :resolve gene-dosage/region-count}}}}

   :input-objects
   {:range
    {:fields
     {:start {:type 'Int}
      :end {:type 'Int}}}
    :date_range
    {:fields
     {:start {:type 'String} ;; TODO chnge to dates
      :end {:type 'String}}} ;; see https://lacinia.readthedocs.io/en/latest/custom-scalars.html
    :genomic_locus
    {:fields
     {:build {:type 'String}
      :chr {:type 'String}
      :start {:type 'Int}
      :end {:type 'Int}}}
    :chromo_locus
    {:fields
     {:build {:type 'String}
      :cyto_locus {:type 'String}}}
    :filters
    {:fields
     {:genes {:type '(list String)}
      :regions {:type '(list String)}
      :diseases {:type '(list String)}
      :haplo_desc {:type '(list String)}
      :genomic_location {:type :genomic_locus}
      :chomo_location {:type :chromo_locus}
      :hi_score {:type 'String}
      :ti_score {:type 'String}
      :protein_coding {:type 'Boolean}
      :omim {:type 'Boolean}
      :morbid {:type 'Boolean}
      :location_overlap {:type 'Boolean}
      :location_contained {:type 'Boolean}
      :gene_disease_validity {:type 'Boolean}
      :clinical_actionability {:type 'Boolean}
      :hi_range {:type :range}
      :pli_range {:type :range}
      :reviewed_range {:type :date_range}
      }}}   

   :queries
   {:gene {:type '(non-null :gene)
           :args {:iri {:type 'String}}
           :resolve gene/gene-query}
    :gene_list {:type '(list :gene)
                :args {:limit {:type 'Int
                               :default-value 10
                               :description "Number of records to return"}
                       :offset {:type 'Int
                                :default-value 0
                                :description "Index to begin returning records from"}
                       :curation_type {:type :CurationActivity
                                       :description 
                                       (str "Limit genes returned to those that have a curation, "
                                            "or a curation of a specific type.")}}
                :resolve gene/gene-list}
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
                    :resolve server-status/server-version-query}
    :dosage_list{:type '(list :gene_dosage_curation)
                 ;; :args {:filters {:type '(list String)}}
                 :args {:filters {:type :filters}}
                 :resolve gene-dosage/dosage-list-query}
    :dosage_query{:type '(non-null :gene_dosage_curation)
                  :args {:iri {:type 'String}}
                  :resolve gene-dosage/gene-dosage-query}
    :totals {:type :totals
             :resolve gene-dosage/totals-query}}})

(defn schema []
  (schema/compile base-schema))

(defn gql-query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  [query-str]
  (lacinia/execute (schema) query-str nil nil))
