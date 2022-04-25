(ns genegraph.source.graphql.schema.variation-descriptor
  (:require [genegraph.database.query :as q]
            [io.pedestal.log :as log]
            [genegraph.database.names :as names]))

(def variation-descriptor
  {:name :VariationDescriptor
   :graphql-type :object
   :description "A descriptor containing a reference to a GA4GH Variation object, commonly an allele."
   :implements [:Resource]
   :fields {:canonical_reference {:type '(list :Resource)
                                  :description "A list canonicalizations considered appropriate for the allele described therein."
                                  :path [:ga4gh/CanonicalReference]}}})

(def vrs-extension
  {:name :Extension
   :graphql-type :object
   :description "A VRS Extension object"
   :implements [:Resource]
   :fields {:name {:type 'String
                   :description "Name of the extension"
                   :path [:vrs/name]}
            :value {:type 'String
                    :description "Value of the extension"
                    :path [:rdf/value]}}})

(def vrs-allele
  {:name :Allele
   :graphql-type :object
   :description "A GA4GH Allele"
   :implements [:Resource]
   :fields {:location {:type :SequenceLocation
                       :path [:vrs/location]}
            :state {:type :LiteralSequenceExpression
                    :path [:vrs/state]}}})

(def vrs-text
  {:name :Text
   :graphql-type :object
   :description "A GA4GH Text variation"
   :implements [:Resource]
   :fields {:definition {:type 'String
                         :path [:vrs/definition]}}})

(def vrs-canonical-variation
  {:name :CanonicalVariation
   :graphql-type :object
   :description "A GA4GH CanonicalVariation"
   :implements [:Resource]
   :fields {:complement {:type 'Boolean
                         :path [:vrs/complement]}
            :variation {:type :Resource
                        :description "Variation. May be an Allele, Text, or other types"
                        :path [:vrs/variation]}}})

(def vrs-literal-sequence-expression
  {:name :LiteralSequenceExpression
   :graphql-type :object
   :description "A literal sequence expression"
   :implements [:Resource]
   :fields {:sequence {:type 'String
                       :path [:vrs/sequence]}}})

(def vrs-sequence-location
  {:name :SequenceLocation
   :graphql-type :object
   :description "A sequence location"
   :implements [:Resource]
   :fields {:interval {:type :SequenceInterval
                       :path [:vrs/interval]}
            :sequence_id {:type 'String
                          :path [:vrs/sequence_id]}}})

(def vrs-number
  {:name :Number
   :graphql-type :object
   :description "A number with a value"
   :implements [:Resource]
   :fields {:value {:type 'Int
                    :path [:vrs/value]}}})

(def vrs-sequence-interval
  {:name :SequenceInterval
   :graphql-type :object
   :description "A sequence interval"
   :implements [:Resource]
   :fields {:start {:type :Number
                    :path [:vrs/start]}
            :end {:type :Number
                  :path [:vrs/end]}}})

(def vrs-expression
  {:name :Expression
   :graphql-type :object
   :description "Expressions of a variation"
   :implements [:Resource]
   :fields {:value {:type 'String
                    :description "Value of the expression"
                    :path [:rdf/value]}
            :syntax {:type 'String
                     :description "A syntax identifier indicating what form the expression is in."
                     :path [:vrs/syntax]}
            :syntax_version {:type 'String
                             :description "A version of the syntax. (Often not provided)"
                             :path [:vrs/syntax-version]}}})

(def vrs-variation-member
  {:name :VariationMember
   :graphql-type :object
   :description "A VariationMember of an grouping of variation representations."
   :implements [:Resource]
   :fields {:expressions {:type '(list :Expression)
                          :description "Expressions for a single variation representation"
                          :path [:vrs/expressions]}}})

(def categorical-variation-descriptor
  {:name :CategoricalVariationDescriptor
   :graphql-type :object
   :description "Descriptor for a categorical variation"
   :implements [:Resource]
   :fields {; TODO may consider using unions to self-document what types these may be
            :value {:type :CanonicalVariation
                    :path [:rdf/value]}
            :xrefs {:type '(list String)
                    :path [:vrs/xrefs]}
            :members {:type '(list :VariationMember)
                      :description "Noncanonical variation representations. Exists alongside the canonical representation of the variation."
                      :path [:vrs/members]}
            :extensions {:type '(list :Extension)
                         :path [:vrs/extensions]}}})

(defn resource-has-predicate?
  "Accepts and RDFResource and a property name key (e.g. :dc/has-version)"
  [resource predicate-key]
  (let [spql "SELECT ?pred_value WHERE { ?resource ?pred ?pred_value }"
        results (q/select spql {:resource resource :pred predicate-key})]
    (< 0 (count results))))

(defn resource-has-edge?
  "Accepts and RDFResource and an incoming or outgoing edge (e.g. [:dc/has-version :<])"
  [resource edge]
  (let [vals (q/ld-> resource [edge])]
    (< 0 (count vals))))

(defn variation-descriptor-resolver
  [context args value]
  (log/info :fn ::variation-descriptor-resolver :args args :value value)
  ;; Variation IRIs in the xrefs predicate are not resources, they are string literals. So cannot use
  ;; the q/ld-> inverse relation lookup, have to use SPARQL explicitly.
  (let [;variation-iri (:variation_iri args)
        {variation-iri :variation_iri} args
        variation-resource (q/resource variation-iri)
        ;descriptor-resources (q/ld-> variation-resource [[:vrs/xrefs :<]])


        ;by-iri (q/select "SELECT ?iri WHERE { ?iri a :vrs/CategoricalVariationDescriptor }"
        ;                 {:iri (str variation)})
        resource-has-type? (fn [r class-kw]
                             (let [type-qualified (str (get names/local-class-names class-kw))]
                               (log/debug :fn :resource-has-type? :r r :class-kw class-kw :type-qualified type-qualified)
                               (let [hasit? (not-empty (q/select "SELECT ?iri WHERE { ?iri a ?type }"
                                                                 {:iri r
                                                                  :type (q/resource type-qualified)}))]
                                 (log/debug :fn :resource-has-type? :hasit? hasit?)
                                 hasit?
                                 )))
        descriptor-resources
        (if (resource-has-type? variation-resource :vrs/CategoricalVariationDescriptor)
          [variation-resource]
          (q/select "SELECT ?descriptor WHERE { ?descriptor :vrs/xrefs ?variation_iri }"
                    {:variation_iri (str variation-resource)}))
        ]
    (log/info :variation-iri variation-iri)
    (log/info :variation-resource variation-resource)
    (log/info :descriptor-resources descriptor-resources)
    (->> descriptor-resources
         (sort-by (fn [r] (q/ld1-> r [:owl/version-info])))
         last)
    ;(let [is-a-version? (resource-has-edge? variation-resource [:dc/is-version-of :>])]
    ;  (if is-a-version?
    ;    variation-resource
    ;    ; Get the latest
    ;    (let [versioned-resources (q/ld-> variation-resource [[:dc/is-version-of :<]])]
    ;      (log/info :versioned-resources versioned-resources)
    ;      (->> versioned-resources
    ;           (sort-by (fn [r] (q/ld1-> r [:owl/version-info])))
    ;           last)
    ;      )))
    ))


(def variation-descriptor-query
  {:name :variation_descriptor_query
   :graphql-type :query
   :description "Find variation descriptors"
   :type :CategoricalVariationDescriptor
   :args {;:iri {:type 'String
          ;      :description "An IRI for the descriptor"}
          :variation_iri {:type 'String
                          :description "An IRI for the thing (variation) described by the descriptors"}
          :version {:type 'String
                    :description (str "Version to retrieve. This accepts a YYYY-MM-DDTHH-mm-ss.SSSZ datetime string, "
                                      "or a static value below:\n"
                                      "LATEST: return the latest version of a record matching this criteria.")
                    :default-value "LATEST"}}
   :resolve variation-descriptor-resolver})
