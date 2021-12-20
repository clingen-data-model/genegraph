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
    [:sepio/FamilyCosegregation :Segregation]]
   :default-type-mapping :GenericResource})

