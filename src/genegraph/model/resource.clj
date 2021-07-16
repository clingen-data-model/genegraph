(ns genegraph.model.resource
  "Definitions for model of RDFResource objects"
  (:require [genegraph.database.query :as q]))


(defn subject-of [_ args value]
  (concat (q/ld-> value [[:sepio/has-subject :<]])
          (q/ld-> value [[:sepio/has-object :<]])))

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
                                              (:foaf/name value))))}
            :type {:type '(list :Resource)
                   :description "The types for this resource."
                   :path [:rdf/type]}
            :description {:type 'String
                          :description "Textual description of this resource"
                          :path [:dc/description]}
            :source {:type :Resource
                     :description "A related resource from which the described resource is derived."
                     :path [:dc/source]}
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
