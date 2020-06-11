(ns genegraph.source.graphql.core
  (:require [genegraph.database.query :as q]
            [genegraph.database.util :refer [tx]]
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
            [genegraph.source.graphql.genetic-condition :as genetic-condition]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]))

(def base-schema 
  {:enums
   {:CurationActivity
    {:description "The curation activities within ClinGen. Each curation is associated with a curation activity."
     :values [:ALL :ACTIONABILITY :GENE_VALIDITY :GENE_DOSAGE]
     }
    :ModeOfInheritance
    {:description "Mode of inheritance for a genetic condition."
     :values [:AUTOSOMAL_DOMINANT :AUTOSOMAL_RECESSIVE :X_LINKED :SEMIDOMINANT :MITOCHONDRIAL :UNDETERMINED]}
    :GeneDosageScore
    {:description "The score assigned to a Gene Dosage curation."
     :values [:ASSOCIATED_WITH_AUTOSOMAL_RECESSIVE_PHENOTYPE
              :MINIMAL_EVIDENCE
              :MODERATE_EVIDENCE
              :NO_EVIDENCE
              :SUFFICIENT_EVIDENCE
              :DOSAGE_SENSITIVITY_UNLIKELY]}
    :GeneValidityClassification
    {:description "The final classification given to a Gene Validity assertion."
     :values [:DEFINITIVE
              :LIMITED
              :MODERATE
              :NO_KNOWN_DISEASE_RELATIONSHIP
              :STRONG
              :DISPUTED
              :REFUTED]}
    :GeneDosageAssertionType
    {:description "The type of gene dosage assertion, either haploinsufficiency or triplosensitivity"
     :values [:HAPLOINSUFFICIENCY_ASSERTION :TRIPLOSENSITIVITY_ASSERTION]}
    :Direction
    {
     :description "Sort direction."
     :values [:ASC :DESC]
     }
    :SortField
    {:description "Sort fields for gene, disease lists and search results."
     :values [:GENE_LABEL :DISEASE_LABEL :REPORT_DATE]}
    :GeneDosageSortField
    {
     :description "Gene dosage sort fields."
     :values [:GENE_REGION :LOCATION :MORBID :OMIM :HAPLO_EVIDENCE :TRIPLO_EVIDENCE :HI_PCT :PLI :REVIEWED_DATE]
     }
    :Build
    {
     :description "Genomic build identifier."
     :values [:GRCH37 :GRCH38]
     }
    :Chromosome
    {:description "Chromosomes."
     :values [:CHR1 :CHR2 :CHR3 :CHR4 :CHR5 :CHR6 :CHR7 :CHR8 :CHR9 :CHR10 :CHR11 :CHR12 :CHR13 :CHR14 :CHR15
              :CHR16 :CHR17 :CHR18 :CHR19 :CHR20 :CHR21 :CHR22 :CHRX :CHRY]
     }}
   
   :interfaces
   {:Resource
     {:description "An RDF Resource; generic type suitable for return when a variety of resources may be returned as the result of a function all"
     :fields {:iri {:type 'String}
              :label {:type 'String}}}

     :GenomicCoordinate
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
    
    :GenomicFeature
    {:description "Genomic feature represented by a Gene or Region"
     :fields {:coordinates {:type '(list :GenomicCoordinate)}}}

    :Curation 
    {:fields {:report_date {:type 'String}}}}

   :objects
   {:Gene
    {:description "A genomic feature (a gene or a region). Along with conditions, one of the basic units of curation."
     :implements [:Resource]
     :fields {:iri {:type 'String
                   :resolve resource/iri
                    :description "IRI representing the gene. Uses NCBI gene identifiers"}
              :curie {:type 'String
                      :resolve resource/curie
                      :description "CURIE of the IRI representing this resource."}
              :label {:type 'String
                      :resolve resource/label
                      :description "Gene symbol"}
              :alternative_label {:type 'String
                                  :resolve resource/alternative-label
                                  :description "Full name of gene"}
              :last_curated_date {:type 'String
                                  :resolve gene/last-curated-date
                                  :description "Most recent date a curation (of any kind) has been performed on this gene."}
              :chromosome_band {:type 'String
                                :resolve gene/chromosome-band
                                :description "Cytogenetic band of gene."}
              :hgnc_id {:type 'String  
                        :resolve gene/hgnc-id
                        :description "HGNC ID of gene"}
              :curation_activities {:type '(list :CurationActivity)
                                    :description "The curation activities that have published reports on the gene"
                                    :resolve gene/curation-activities}
              :genetic_conditions {:type '(list :GeneticCondition)
                                   :resolve gene/conditions
                                   :description "Genetic conditions associated with gene. These represent gene-disease pairs for which a curation exists associated with the given gene."}
              :dosage_curation {:type :GeneDosageCuration
                                :resolve gene/dosage-curation
                                :description "Gene Dosage curation associated with the gene or region."}}}

    :Genes
    {:description "A collection of genes."
     :fields {:gene_list {:type '(list :Gene)}
              :count {:type 'Int}}}

    :GeneticCondition
    {:description "A condition described by some combination of gene, disease, and mode of inheritance (usually at least gene and disease)."
     :fields {:gene {:type :Gene
                     :resolve genetic-condition/gene
                     :description "The gene associated with this genetic condition."}
              :disease {:type :Disease
                        :resolve genetic-condition/disease
                        :description "The disease associated with this genetic condition."}
              :mode_of_inheritance {:type :ModeOfInheritance
                                    :resolve genetic-condition/mode-of-inheritance
                                    :description "The mode of inheritance associated with this genetic condition."}
              :actionability_curations {:type '(list :ActionabilityCuration)
                                       :resolve genetic-condition/actionability-curations
                                       :description "Actionability curations associated with this genetic condition. Unlike gene_validity and dosage, there may be more than one per genetic condition, as the condition may be curated in both pediatric and adult contexts."}
              :gene_validity_assertions {:type '(list :GeneValidityAssertion)
                                        :resolve genetic-condition/gene-validity-curation
                                        :description "Gene Validity curation associated with this genetic condition."}
              :gene_dosage_assertions {:type '(list :DosageAssertion)
                                       :resolve genetic-condition/gene-dosage-curation
                                       :description "Dosage sensitivity curation associated with this genetic condition."}}}

    :Coordinate
    {:description "a genomic coordinate"
     :implements [:GenomicCoordinate]
     :fields {:build {:type 'String
                      :resolve coordinate/build
                      :description "The genomic build designation."}
              :assembly {:type 'String
                         :resolve coordinate/assembly
                         :description "The NC reference assembly."}
              :chromosome {:type 'String
                           :resolve coordinate/chromosome
                           :description "The chromosome."}
              :strand {:type 'String
                       :resolve coordinate/strand
                       :description "The strand."}
              :start_pos {:type 'Int
                          :resolve coordinate/start-pos
                          :description "The coordinate starting position."}
              :end_pos {:type 'Int
                        :resolve coordinate/end-pos
                        :description "The coordinate end position."}}}
    
    :GeneFeature
    {:description "A gene feature"
     :implements [:Resource :GenomicFeature]
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
              :coordinates {:type '(list :Coordinate)
                            :resolve gene-feature/coordinates
                            :description "Coordinates of the feature"}}}
    
    :RegionFeature
    {:description "A region feature"
     :implements [:Resource :GenomicFeature]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI representing the region"}
              :label {:type 'String
                      :resolve resource/label
                      :description "Region symbol"}
              :chromosomal_band {:type 'String
                                :resolve region-feature/chromosomal-band
                                :description "The chromosomal location"}
              :coordinates {:type '(list :Coordinate)
                            :resolve region-feature/coordinates
                            :description "Coordinates of the feature"}}}

    :Disease
    {:description "A disease or condition. May be a genetic condition, linked to a specific disease or mode of inheritance. Along with gene, one of the basic units of curation."
     :implements [:Resource]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI for the condition. Currently MONDO ids are supported."}
              :curie {:type 'String
                      :resolve resource/curie
                      :description "CURIE of the IRI representing this resource."}
              :label {:type 'String
                      :resolve resource/label
                      :description "Label for the condition."}
              :description {:type 'String
                            :resolve condition/description
                            :description "Disease description name."}
              :superclasses {:type '(list :Disease)
                             :resolve resource/all-superclasses
                             :description "All superclasses of the disease"}
              :direct_superclasses {:type '(list :Disease)
                                    :resolve resource/direct-superclasses
                                    :description "Direct superclasses of the disease"}
              :subclasses {:type '(list :Disease)
                           :resolve resource/all-subclasses
                           :description "All subclasses of the disease"}
              :direct_subclasses {:type '(list :Disease)
                                  :resolve resource/direct-subclasses
                                  :description "Direct subclasses of the disease"}
              :previous_names {:type '(list String)
                            :resolve condition/previous-names
                               :description "Previous condition names."}
              :aliases {:type '(list String)
                        :resolve condition/aliases
                        :description "Alias condition names."}
              :equivalent_conditions {:type '(list String)
                                      :resolve condition/equivalent-conditions
                                      :description "Equivalent condition names."}
              :last_curated_date {:type 'String
                                  :resolve condition/last-curated-date
                                  :description "Most recent date a curation (of any kind) has been performed on this condition."}
               :curation_activities {:type '(list :CurationActivity)
                                    :description "The curation activities that have published reports on the disease"
                                    :resolve condition/curation-activities}
              :genetic_conditions {:type '(list :GeneticCondition)
                                   :resolve condition/genetic-conditions
                                   :description "Curated genetic conditions associated with the disease"}}}

    :Diseases
    {:description "A collection of diseases."
     :fields {:disease_list {:type '(list :Disease)}
              :count {:type 'Int}}}

    :Evidence
    {:description "An evidence item, typically used in support of an assertion made as a part of a curation"
     :fields {:source {:type 'String
                       :resolve evidence/source
                       :description "Origin of the evidence, i.e. PubMed reference"}
              :description {:type 'String
                            :resolve evidence/description
                            :description "Description of the evidence being cited."}}}


    :GeneDosageCuration
    {:description "A complete gene dosage curation containing one or more dosage propositions"
     :implements [:Resource]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI for the Dosage curation."}
              :curie {:type 'String
                      :resolve resource/curie
                      :description "Local identifier for the Dosage curation."}
              :label {:type 'String
                      :resolve gene-dosage/label
                      :description "Label of the Gene or Region curation."}
              :wg_label {:type 'String
                         :resolve gene-dosage/wg-label
                         :description "Working Group label."}
              :report_date {:type 'String
                            :resolve gene-dosage/report-date
                            :description "The date of the report."}
              :genomic_feature {:type :GenomicFeature
                                :resolve gene-dosage/genomic-feature
                                :description "The list of genomic features."}
              :haploinsufficiency_assertion {:type :DosageAssertion
                                             :resolve gene-dosage/haplo
                                             :description "Haploinsufficiency"}
              :triplosensitivity_assertion {:type :DosageAssertion
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
                     :resolve gene-dosage/omim
                     :description "The OMIM curie for thsi gene or region."}
              :pli_score {:type 'String
                          :resolve gene-dosage/pli-score
                          :description "Loss intolerence (pLI)"}
              :location_relationship {:type 'String
                                      :resolve gene-dosage/location-relationship
                                      :description "Location relationship"}}}

    :DosageAssertion
    {:description "An individual dosage proposition, either a Haplo Insufficiency proposition or a Triplo Sensitivity proposition"
     :implements [:Resource :Curation]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "Identifier for the curation"}
              :curie {:type 'String
                      :resolve resource/curie
                      :description "Local identifier for the curation"}
              :label {:type 'String
                      :resolve resource/label
                      :description "Curation label"}
              :wg_label {:type 'String
                         :resolve dosage-proposition/wg-label
                         :description "Label for the working group responsible for the curation"}
              :classification_description {:type 'String
                                           :resolve dosage-proposition/classification-description
                                           :description "Summary of the classification and the rationale behind it"}
              :assertion_type {:type :GeneDosageAssertionType
                               :resolve dosage-proposition/assertion-type
                               :description "Type of assertion (haploinsufficiency/triplosensitivity"}
              :report_date {:type 'String
                            :resolve dosage-proposition/report-date
                            :description "Date the report was last issued by the working group."}
              :evidence {:type '(list :Evidence)
                         :resolve dosage-proposition/evidence
                         :description "Evidence relating to the gene dosage curation."}
              :score {:type :GeneDosageScore
                      :resolve dosage-proposition/score
                      :description "Sufficiency score"}
              :phenotypes {:type 'String
                           :resolve dosage-proposition/phenotypes
                           :description "The phenotypes to which the evidence applies"}
              :comments {:type 'String
                         :resolve dosage-proposition/comments
                         :description "Comments related to this curation"}
              :gene {:type :Gene
                     :resolve dosage-proposition/gene
                     :description "Gene associated with this assertion"}
              :disease {:type :Disease
                     :resolve dosage-proposition/disease
                     :description "Disease associated with this assertion"}}}

    :ActionabilityCuration
    {:implements [:Resource :Curation]
     :fields 
     {:iri {:type 'String
            :resolve resource/iri}
      :curie {:type 'String
              :resolve resource/curie}
      :label {:type 'String :resolve resource/label}
      :report_date {:type 'String :resolve actionability/report-date}
      :wg_label {:type 'String :resolve actionability/wg-label}
      :report_id {:type 'String :resolve actionability/report-id}
      :classification_description {:type 'String :resolve actionability/classification-description}
      :conditions {:type '(list :Disease)
                   :resolve actionability/conditions}
      :source {:type 'String :resolve actionability/source}}}

    :GeneValidityAssertion
    {:implements [:Resource :Curation]
     :fields
     {:iri {:type 'String
            :resolve resource/iri
            :description "IRI identifying this gene validity curation."}
      :curie {:type 'String
              :resolve resource/curie
              :description "CURIE of the IRI identifying this gene validity curation."}
      :label {:type 'String
              :resolve resource/label
              :description "Label identifying this gene validity curation."}
      :report_date {:type 'String
                    :resolve gene-validity/report-date
                    :description "Date gene validity report was issued."}
      :classification {:type :GeneValidityClassification
                       :description "Final classification of this gene validity curation."
                       :resolve gene-validity/classification}
      :gene {:type :Gene
             :description "Gene associated with this curation"
             :resolve gene-validity/gene}
      :disease {:type :Disease
                :description "Disease associated with this curation"
                :resolve gene-validity/disease}
      :mode_of_inheritance {:type :ModeOfInheritance
                            :description "Mode of inheritance associated with this curation"
                            :resolve gene-validity/mode-of-inheritance}}}

    :GeneValidityAssertions
    {:description "A collection of gene validity curations."
     :fields {:curation_list {:type '(list :GeneValidityAssertion)}
              :count {:type 'Int}}}

    :Agent
    {:implements [:Resource]
     :description "A person or group. In this context, generally a ClinGen Domain Working Group responsible for producing one or more curations."
     :fields
     {:iri {:type 'String
            :resolve resource/iri
            :description "IRI identifying this gene validity curation."}
      :curie {:type 'String
              :description "CURIE of the IRI identifying this gene validity curation."}
      :label {:type 'String
              :resolve resource/label
              :description "Label identifying this gene validity curation."}}}

    :ServerStatus
    {:fields
     {:migration_version {:type 'String
                          :resolve server-status/migration-version}}}

    :Totals
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
     {:build {:type :Build}
      :chr {:type :Chromosome}
      :start {:type 'Int} 
      :end {:type 'Int}}}
    :chromo_locus
    {:fields
     {:build {:type :Build}
      :genomic_feature_location {:type 'String}}}
    :Sort
    {:fields
     {:field {:type :SortField :default-value :GENE_LABEL}
      :direction {:type :Direction :default-value :ASC}}}
    :sort_spec
    {:fields
     {:sort_field {:type :GeneDosageSortField}
      :direction {:type :Direction :default-value :ASC}}}
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
      :reviewed_range {:type :date_range}}}
    :paging
    {:fields
     {:limit {:type 'Int}
      :offset {:type 'Int}}}
    :sorting
    {:fields
     {:sort_fields {:type '(list :sort_spec)}}}}   

   :queries
   {:gene {:type '(non-null :Gene)
           :args {:iri {:type 'String}}
           :resolve gene/gene-query}
    :genes {:type :Genes
            :resolve gene/genes
            :args {:limit {:type 'Int
                           :default-value 10
                           :description "Number of records to return"}
                   :offset {:type 'Int
                            :default-value 0
                            :description "Index to begin returning records from"}
                   :text {:type 'String
                          :description (str "Filter list for genes including text in symbol"
                                            "previous symbols, names, or previous names.")}
                   :curation_activity {:type :CurationActivity
                                       :description 
                                       (str "Limit genes returned to those that have a curation, "
                                            "or a curation of a specific type.")}
                   :sort {:type :Sort
                          :description (str "Order in which to sort genes.")}}}
    :gene_list {:type '(list :Gene)
                :deprecated "use Genes field instead"
                :args {:limit {:type 'Int
                               :default-value 10
                               :description "Number of records to return"}
                       :offset {:type 'Int
                                :default-value 0
                                :description "Index to begin returning records from"}
                       :curation_type {:type :CurationActivity
                                       :description 
                                       (str "Limit genes returned to those that have a curation, "
                                            "or a curation of a specific type.")}
                       :sort {:type :Sort
                              :description (str "Order in which to sort genes. Supported fields: "
                                                "GENE_LABEL")}}
                :resolve gene/gene-list}
    :disease {:type '(non-null :Disease)
                :args {:iri {:type 'String}}
                :resolve condition/condition-query}
    :diseases {:type :Diseases
               :args {:limit {:type 'Int
                              :default-value 10
                              :description "Number of records to return"}
                      :offset {:type 'Int
                               :default-value 0
                               :description "Index to begin returning records from"}
                      :curation_activity {:type :CurationActivity
                                          :description 
                                          (str "Limit genes returned to those that have a"
                                               " curation, or a curation of a specific type.")}
                      :text {:type 'String
                             :description (str "Filter list for genes including text in name"
                                            "and synonyms.")}
                      :sort {:type :Sort
                             :description (str "Order in which to sort diseases")}}
               :resolve condition/diseases}
    :disease_list {:type '(list :Disease)
                   :deprecated "Use diseases instead."
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
                   :resolve condition/disease-list}
    :gene_validity_assertions {:type :GeneValidityAssertions
                              :resolve gene-validity/gene-validity-curations
                              :args {:limit {:type 'Int
                                             :default-value 10
                                             :description "Number of records to return"}
                                     :offset {:type 'Int
                                              :default-value 0
                                              :description "Index to begin returning records from"}
                                     :text {:type 'String
                                            :description (str "Filter list including "
                                                              "text in gene, disease and "
                                                              "synonyms.")}
                                     :sort {:type :Sort
                                            :description (str "Order in which to sort genes. "
                                                              "Supported fields: GENE_LABEL")}}}
    :gene_validity_list {:type '(list :GeneValidityAssertion)
                         :deprecated "Use gene_validity_assertions instead"
                         :resolve gene-validity/gene-validity-list
                         :args {:limit {:type 'Int
                                        :default-value 10
                                        :description "Number of records to return"}
                                :offset {:type 'Int
                                         :default-value 0
                                         :description "Index to begin returning records from"}
                                :sort {:type :Sort
                                       :description (str "Order in which to sort genes. "
                                                         "Supported fields: GENE_LABEL")}}}
    :actionability {:type '(non-null :ActionabilityCuration)
                    :args {:iri {:type 'String}}
                    :resolve actionability/actionability-query}
    :server_status {:type '(non-null :ServerStatus)
                    :resolve server-status/server-version-query}
    :dosage_list{:type '(list :GeneDosageCuration)
                 :args {:filters {:type :filters}
                        :paging {:type :paging}
                        :sorting {:type :sorting}}
                 :resolve gene-dosage/dosage-list-query}
    :dosage_query{:type '(non-null :GeneDosageCuration)
                  :args {:iri {:type 'String}}
                  :resolve gene-dosage/gene-dosage-query}
    :totals {:type :Totals
             :resolve gene-dosage/totals-query}}})

(defn schema []
  (schema/compile base-schema))

(defn gql-query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  [query-str]
  (tx (lacinia/execute (schema) query-str nil nil)))
