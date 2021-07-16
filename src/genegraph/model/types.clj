(ns genegraph.model.types)

(def rdf-to-graphql-type-mappings
  {:type-mappings
   [[:sepio/Assertion :Statement]
    [:sepio/Proposition :Statement]
    [:prov/Agent :Agent]
    [:sepio/EvidenceLine :Statement]
    [:sepio/ProbandWithVariantEvidenceItem :ProbandEvidence]
    [:ga4gh/VariationDescriptor :VariationDescriptor]]
   :default-type-mapping :GenericResource})

