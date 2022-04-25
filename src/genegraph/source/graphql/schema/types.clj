(ns genegraph.source.graphql.schema.types)

(def rdf-to-graphql-type-mappings
  {:type-mappings
   [[:sepio/Assertion :Statement]
    [:sepio/Proposition :Statement]
    [:prov/Agent :Agent]
    [:sepio/EvidenceLine :Statement]
    [:dc/BibliographicResource :BibliographicResource]
    [:sepio/ProbandWithVariantEvidenceItem :ProbandEvidence]
    [:sepio/VariantEvidenceItem :VariantEvidence]
    [:ga4gh/VariationDescriptor :VariationDescriptor]
    [:vrs/CategoricalVariationDescriptor :CategoricalVariationDescriptor]
    [:vrs/Allele :Allele]
    [:vrs/Text :Text]
    [:vrs/CanonicalVariation :CanonicalVariation]
    [:vrs/SequenceLocation :SequenceLocation]
    [:vrs/SequenceInterval :SequenceInterval]
    [:vrs/LiteralSequenceExpression :LiteralSequenceExpression]
    [:vrs/Number :Number]
    [:vrs/Expression :Expression]
    [:vrs/Extension :Extension]
    [:vrs/VariationMember :VariationMember]
    [:sepio/FamilyCosegregation :Segregation]
    [:sepio/ValueSet :ValueSet]
    [:pco/Family :Family]]
   :default-type-mapping :GenericResource})

