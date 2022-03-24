(ns genegraph.source.graphql.schema.variation-descriptor
  (:require [genegraph.database.query :as q]))

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
   :fields {:atype {:type 'String
                    :description "Type"
                    ;:path [:rdf/type]
                    :resolve (fn [context args value] "Extension")
                    }
            :name {:type 'String
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
   :fields {:_id {:type 'String
                  :resolve (fn [context args value] (str value))}
            :location {:type :SequenceLocation
                       :path [:vrs/location]}
            :state {:type :LiteralSequenceExpression
                    :path [:vrs/state]}}})

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
   :fields {:_id {:type 'String
                  :resolve (fn [context args value] (str value))}
            :interval {:type :SequenceInterval
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

(def categorical-variation-descriptor
  {:name :CategoricalVariationDescriptor
   :graphql-type :object
   :description "Descriptor for a categorical variation"
   :implements [:Resource]
   :fields {:_id {:type 'String
                  :resolve (fn [context args value] (str value))}
            :atype {:type 'String
                    :resolve (fn [context args value] "CategoricalVariationDescriptor")}
            ; TODO may consider using more unions to self-document what types these may be
            :object {:type :Resource
                     :path [:sepio/has-object]}
            :extensions {:type '(list :Extension)
                         :path [:vrs/extensions]}
            }})
