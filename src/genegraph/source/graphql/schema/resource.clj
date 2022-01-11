(ns genegraph.source.graphql.schema.resource
  "Definitions for model of RDFResource objects"
  (:require [genegraph.database.query :as q]))


(defn subject-of [_ args value]
  (concat (q/ld-> value [[:sepio/has-subject :<]])
          (q/ld-> value [[:sepio/has-object :<]])))

(def type-query (q/create-query "select ?type where {?resource a /  :rdfs/subClassOf * ?type}"))

(defn rdf-types [_ args value]
  (if (:inferred args)
    (type-query {:resource value})
    (q/ld-> value [:rdf/type])))

(def resource-interface
  {:name :Resource
   :graphql-type :interface
   :description "An RDF Resource; type common to all addressable entities in Genegraph"
   :fields {:iri {:type 'String
                  :description "The IRI for this resource."
                  :resolve (fn [_ _ value] (str value))}
            :curie {:type 'String
                    :description "The CURIE internal to Genegraph for this resource."
                    :resolve (fn [_ _ value] (q/curie value))}
            :label {:type 'String
                    :description "The label for this resouce."
                    :resolve (fn [_ _ value]
                               (first (concat (:skos/preferred-label value)
                                              (:rdfs/label value)
                                              (:foaf/name value)
                                              (:dc/title value))))}
            :type {:type '(list :Resource)
                   :description "The types for this resource."
                   :args {:inferred {:type 'Boolean}}
                   :resolve rdf-types}
            :description {:type 'String
                          :description "Textual description of this resource"
                          :path [:dc/description]}
            :source {:type :BibliographicResource
                     :description "A related resource from which the described resource is derived."
                     :path [:dc/source]}
            :used_as_evidence_by {:type :Statement
                                  :description "Statements that use this resource as evidence"
                                  :path [[:sepio/has-evidence :<]]}
            :subject_of {:type '(list :Statement)
                         :description "Assertions (or propositions) that have this resource as a subject (or object)."
                         ;; TODO implement as path when inverse; optional paths are done
                         ;; in Jena mapping. Exists as function for now.
                         :resolve subject-of}}})

(def generic-resource
  {:name :GenericResource
   :graphql-type :object
   :description "A generic implementation of an RDF Resource, suitable when no other type can be found or is appropriate"
   :implements [:Resource]})

(def resource-query
  {:name :resource
   :graphql-type :query
   :description "Find a resource by IRI or CURIE"
   :args {:iri {:type 'String}}
   :type :Resource
   :resolve (fn [_ args _]
              (q/resource (:iri args)))})
