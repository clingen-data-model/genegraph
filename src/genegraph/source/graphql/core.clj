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
            [genegraph.source.graphql.affiliation :as affiliation]
            [genegraph.source.graphql.suggest :as suggest]
            [genegraph.source.graphql.drug :as drug]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def base-schema 
  {:enums
   {:CurationActivity
    {:description "The curation activities within ClinGen. Each curation is associated with a curation activity."
     :values [:ALL :ACTIONABILITY :GENE_VALIDITY :GENE_DOSAGE]
     }
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
     }
    :Suggester
    {:description "Suggesters."
     :values [:GENE :DISEASE :DRUG]
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

    :ModeOfInheritance
    {:description "Mode of inheritance for a genetic condition."
     :implements [:Resource]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI for the condition. Currently MONDO ids are supported."}
              :curie {:type 'String
                      :resolve resource/curie
                      :description "CURIE of the IRI representing this resource."}
              :label {:type 'String
                      :resolve resource/label
                      :description "Label for the condition."}}}


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
                                   :description "Curated genetic conditions associated with the disease"}
              :subclasses {:type '(list :Disease)
                           :resolve condition/subclasses
                           :description "All subclasses of this disease, including all descendants"}
              :superclasses {:type '(list :Disease)
                             :resolve condition/superclasses
                             :description "All superclasses of this disease, including all ancestors."}
              :direct_subclasses {:type '(list :Disease)
                                  :resolve condition/direct-subclasses
                                  :description "direct subclasses of this disease only."}
              :direct_superclasses {:type '(list :Disease)
                                    :resolve condition/direct-superclasses
                                    :description "direct superclasses of this disease only."}}}

    :Diseases
    {:description "A collection of diseases."
     :fields {:disease_list {:type '(list :Disease)}
              :count {:type 'Int}}}

    :Classification
    {:description "The result of an assertion relative to a proposition"
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI identifying this classification"}
              :curie {:type 'String
                      :resolve resource/curie
                      :description "CURIE of the IRI identifying this resource"}
              :label {:type 'String
                      :resolve resource/label
                      :description "Label for this resourcce"}}}

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

    :DosageClassification
    {:description (str "Classification for a gene dosage assertion that mirrors the scores "
                       "in the gene dosage process. Reflects a reasoning on the SEPIO"
                       " structure, and not the SEPIO assertion itself.")
     :fields {:label {:type 'String}
              :ordinal {:type 'Int}
              :enum_value {:type :GeneDosageScore}}}

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
              :dosage_classification {:type :DosageClassification
                                      :resolve dosage-proposition/dosage-classification
                                      :description 
                                      (str "Classification for a gene dosage assertion "
                                           "that mirrors the scores in the gene dosage process.")}
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
      :classification {:type :Classification
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
                            :resolve gene-validity/mode-of-inheritance}
      :attributed_to {:type :Agent
                      :description "Primary affiliation responsible for this curation"
                      :resolve gene-validity/attributed-to}
      :specified_by {:type :Criteria
                     :description "Criteria used by the curators to create the gene validity assertion"
                     :resolve gene-validity/specified-by}
      :legacy_json {:type 'String
                    :description "Legacy JSON from the GCI."
                    :resolve gene-validity/legacy-json}}}

    :GeneValidityAssertions
    {:description "A collection of gene validity curations."
     :fields {:curation_list {:type '(list :GeneValidityAssertion)}
              :count {:type 'Int}}}

    :Criteria
    {:implements [:Resource]
     :description "Criteria used to perform curation."
     :fields
     {:iri {:type 'String
            :resolve resource/iri
            :description "IRI identifying this resource"}
      :curie {:type 'String
              :resolve resource/curie
              :description "CURIE of the IRI identifying this resource"}
      :label {:type 'String
              :resolve resource/label
              :description "Label for this resourcce"}}}

    :Agents
    {:description (str "A list of agents, with a possible limit, and a count of the total number"
                       " matching the query")
     :fields
     {:agent_list {:type '(list :Agent)}
      :count {:type 'Int}}}

    :Agent
    {:implements [:Resource]
     :description (str  "A person or group. In this context, generally a ClinGen Domain Working"
                        " Group responsible for producing one or more curations.")
     :fields
     {:iri {:type 'String
            :resolve resource/iri
            :description "IRI identifying this agent."}
      :curie {:type 'String
              :resolve resource/curie
              :description "CURIE of the IRI identifying this agent."}
      :label {:type 'String
              :resolve resource/label
              :description "Name of the agent"}

      :gene_validity_assertions
      {:type :GeneValidityAssertions
       :resolve affiliation/gene-validity-assertions
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
      
      :genes
      {:type :Genes
       :resolve affiliation/curated-genes
       :args {:limit {:type 'Int
                      :default-value 10
                      :description "Number of records to return"}
              :offset {:type 'Int
                       :default-value 0
                       :description "Index to begin returning records from"}
              :text {:type 'String
                     :description (str "Filter list for genes including text in symbol"
                                       "previous symbols, names, or previous names.")}
              :sort {:type :Sort
                     :description (str "Order in which to sort genes.")}}}

      :diseases
      {:type :Diseases
       :resolve affiliation/curated-diseases
       :args {:limit {:type 'Int
                      :default-value 10
                      :description "Number of records to return"}
              :offset {:type 'Int
                       :default-value 0
                       :description "Index to begin returning records from"}
              :text {:type 'String
                     :description (str "Filter list for genes including text in symbol"
                                       "previous symbols, names, or previous names.")}
              :sort {:type :Sort
                     :description (str "Order in which to sort genes.")}}}}}

    :ServerStatus
    {:fields
     {:migration_version {:type 'String
                          :resolve server-status/migration-version}}}

    :Totals
    {:fields
     {:total {:type 'Int :resolve gene-dosage/total-count}
      :genes  {:type 'Int :resolve gene-dosage/gene-count}
      :regions  {:type 'Int :resolve gene-dosage/region-count}}}

    :Suggestion
    {:fields
     {:type {:type 'String :resolve suggest/suggest-type}
      :iri {:type 'String :resolve suggest/iri}
      :curie {:type 'String :resolve suggest/curie}
      :text {:type 'String :resolve suggest/text}
      :highlighted {:type 'String :resolve suggest/highlighted-text}
      :curations  {:type '(list :CurationActivity) :resolve suggest/curations}
      :weight  {:type 'Int :resolve suggest/weight}}}

    :Drug
    {:description "RxNorm normalized names for clinical drugs."
     :implements [:Resource]
     :fields {:iri {:type 'String
                    :resolve resource/iri
                    :description "IRI for the drug. Currently RXNorm ids are supported."}
              :curie {:type 'String
                      :resolve resource/curie
                      :description "CURIE of the IRI representing this resource."}
              :label {:type 'String
                      :resolve resource/label
                      :description "Label for the condition."}
              :aliases {:type '(list String)
                        :resolve drug/aliases
                        :description "Alias drug names."}}}
    
    :Drugs
    {:description "A collection of drugs."
     :fields {:drug_list {:type '(list :Drug)}
              :count {:type 'Int}}}}

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
    :affiliations {:type :Agents
                   :args {:limit {:type 'Int
                                  :default-value 10
                                  :description "Number of records to return"}
                          :offset {:type 'Int
                                   :default-value 0
                                   :description "Index to begin returning records from"}
                          :text {:type 'String
                                 :description (str "Filter list for genes including text in name"
                                                   "and synonyms.")}}
                   :resolve affiliation/affiliations}
    :affiliation {:type :Agent
                  :args {:iri {:type 'String}}
                  :resolve affiliation/affiliation-query}
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
    :gene_validity_assertion {:type :GeneValidityAssertion
                              :resolve gene-validity/gene-validity-assertion-query
                              :args {:iri {:type 'String}}}
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
             :resolve gene-dosage/totals-query}
    :suggest {:type '(list :Suggestion)
              :args {:suggest {:type :Suggester
                               :description "The suggester to submit request suggestions from."}
                     :text {:type 'String
                            :description "The text for which suggestions will be generated."}
                     :count {:type 'Int
                             :default-value 10
                             :description "Number of suggestions to return."}
                     :contexts {:type '(list :CurationActivity)
                                :default-value '(list :ALL)
                                :description "List of Curation Activities to filter suggestions with."}}
              :resolve suggest/suggest}
    :drug {:type '(non-null :Drug)
                :args {:iri {:type 'String}}
                :resolve drug/drug-query}
    :drugs {:type :Drugs
            :args {:limit {:type 'Int
                           :default-value 10
                           :description "Number of records to return"}
                   :offset {:type 'Int
                            :default-value 0
                            :description "Index to begin returning records from"}
                   :text {:type 'String
                          :description (str "Filter list for drugs including text in name"
                                            "and synonyms.")}
                   :sort {:type :Sort
                          :description (str "Order in which to sort drugs")}}
               :resolve drug/drugs}
}})

(defn resolver-map []
  {:actionability/actionability-query actionability/actionability-query
   :actionability/classification-description actionability/classification-description
   :actionability/conditions actionability/conditions
   :actionability/report-date actionability/report-date
   :actionability/report-id actionability/report-id
   :actionability/source actionability/source
   :actionability/wg-label actionability/wg-label
   :affiliation/affiliation-query affiliation/affiliation-query
   :affiliation/affiliations affiliation/affiliations
   :affiliation/curated-diseases affiliation/curated-diseases
   :affiliation/curated-genes affiliation/curated-genes
   :affiliation/gene-validity-assertions affiliation/gene-validity-assertions
   :condition/aliases condition/aliases
   :condition/condition-query condition/condition-query
   :condition/curation-activities condition/curation-activities
   :condition/description condition/description
   :condition/direct-subclasses condition/direct-subclasses
   :condition/direct-superclasses condition/direct-superclasses
   :condition/disease-list condition/disease-list
   :condition/diseases condition/diseases
   :condition/equivalent-conditions condition/equivalent-conditions
   :condition/genetic-conditions condition/genetic-conditions
   :condition/last-curated-date condition/last-curated-date
   :condition/previous-names condition/previous-names
   :condition/subclasses condition/subclasses
   :condition/superclasses condition/superclasses
   :coordinate/assembly coordinate/assembly
   :coordinate/build coordinate/build
   :coordinate/chromosome coordinate/chromosome
   :coordinate/end-pos coordinate/end-pos
   :coordinate/start-pos coordinate/start-pos
   :coordinate/strand coordinate/strand
   :dosage-proposition/assertion-type dosage-proposition/assertion-type
   :dosage-proposition/classification-description dosage-proposition/classification-description
   :dosage-proposition/comments dosage-proposition/comments
   :dosage-proposition/disease dosage-proposition/disease
   :dosage-proposition/dosage-classification dosage-proposition/dosage-classification
   :dosage-proposition/evidence dosage-proposition/evidence
   :dosage-proposition/gene dosage-proposition/gene
   :dosage-proposition/phenotypes dosage-proposition/phenotypes
   :dosage-proposition/report-date dosage-proposition/report-date
   :dosage-proposition/score dosage-proposition/score
   :dosage-proposition/wg-label dosage-proposition/wg-label
   :drug/aliases drug/aliases
   :drug/drug-query drug/drug-query
   :drug/drugs drug/drugs
   :evidence/description evidence/description
   :evidence/source evidence/source
   :gene/chromosome-band gene/chromosome-band
   :gene/conditions gene/conditions
   :gene/curation-activities gene/curation-activities
   :gene/dosage-curation gene/dosage-curation
   :gene/gene-list gene/gene-list
   :gene/gene-query gene/gene-query
   :gene/genes gene/genes
   :gene/hgnc-id gene/hgnc-id
   :gene/last-curated-date gene/last-curated-date
   :gene-dosage/dosage-list-query gene-dosage/dosage-list-query
   :gene-dosage/gene-count gene-dosage/gene-count
   :gene-dosage/gene-dosage-query gene-dosage/gene-dosage-query
   :gene-dosage/genomic-feature gene-dosage/genomic-feature
   :gene-dosage/haplo gene-dosage/haplo
   :gene-dosage/haplo-index gene-dosage/haplo-index
   :gene-dosage/label gene-dosage/label
   :gene-dosage/location-relationship gene-dosage/location-relationship
   :gene-dosage/morbid gene-dosage/morbid
   :gene-dosage/morbid-phenotypes gene-dosage/morbid-phenotypes
   :gene-dosage/omim gene-dosage/omim
   :gene-dosage/pli-score gene-dosage/pli-score
   :gene-dosage/region-count gene-dosage/region-count
   :gene-dosage/report-date gene-dosage/report-date
   :gene-dosage/total-count gene-dosage/total-count
   :gene-dosage/totals-query gene-dosage/totals-query
   :gene-dosage/triplo gene-dosage/triplo
   :gene-dosage/wg-label gene-dosage/wg-label
   :gene-feature/alias-symbols gene-feature/alias-symbols
   :gene-feature/chromosomal-band gene-feature/chromosomal-band
   :gene-feature/coordinates gene-feature/coordinates
   :gene-feature/function gene-feature/function
   :gene-feature/gene-type gene-feature/gene-type
   :gene-feature/hgnc-id gene-feature/hgnc-id
   :gene-feature/locus-type gene-feature/locus-type
   :gene-feature/previous-symbols gene-feature/previous-symbols
   :gene-validity/attributed-to gene-validity/attributed-to
   :gene-validity/classification gene-validity/classification
   :gene-validity/disease gene-validity/disease
   :gene-validity/gene gene-validity/gene
   :gene-validity/gene-validity-assertion-query gene-validity/gene-validity-assertion-query
   :gene-validity/gene-validity-curations gene-validity/gene-validity-curations
   :gene-validity/gene-validity-list gene-validity/gene-validity-list
   :gene-validity/legacy-json gene-validity/legacy-json
   :gene-validity/mode-of-inheritance gene-validity/mode-of-inheritance
   :gene-validity/report-date gene-validity/report-date
   :gene-validity/specified-by gene-validity/specified-by
   :genetic-condition/actionability-curations genetic-condition/actionability-curations
   :genetic-condition/disease genetic-condition/disease
   :genetic-condition/gene genetic-condition/gene
   :genetic-condition/gene-dosage-curation genetic-condition/gene-dosage-curation
   :genetic-condition/gene-validity-curation genetic-condition/gene-validity-curation
   :genetic-condition/mode-of-inheritance genetic-condition/mode-of-inheritance
   :region-feature/chromosomal-band region-feature/chromosomal-band
   :region-feature/coordinates region-feature/coordinates
   :resource/alternative-label resource/alternative-label
   :resource/curie resource/curie
   :resource/iri resource/iri
   :resource/label resource/label
   :server-status/migration-version server-status/migration-version
   :server-status/server-version-query server-status/server-version-query
   :suggest/curations suggest/curations
   :suggest/curie suggest/curie
   :suggest/highlighted-text suggest/highlighted-text
   :suggest/iri suggest/iri
   :suggest/suggest suggest/suggest
   :suggest/suggest-type suggest/suggest-type
   :suggest/text suggest/text
   :suggest/weight suggest/weight}) 

(defn schema []
  (-> (io/resource "graphql-schema.edn")
      slurp
      edn/read-string
      (util/attach-resolvers (resolver-map))
      schema/compile))

(defn gql-query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  [query-str]
  (tx (lacinia/execute (schema) query-str nil nil)))
