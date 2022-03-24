(ns genegraph.source.graphql.schema.resource
  "Definitions for model of RDFResource objects"
  (:require [genegraph.database.query :as q]
            [io.pedestal.log :as log]
            [clojure.string :as s]))


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
            :in_scheme {:type '(list :ValueSet)
                        :description "Relates a resource (for example a concept) to a concept scheme in which it is included."
                        :path [:skos/is-in-scheme]}
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

(defn record-metadata-query-resolver
  "Given an iri (which can be in CURIE form), return version information:
  - version
  - replaces
  - replaced_by
  - is_version_of"
  [context args value]
  (let [r (q/resource (:iri args))]
    r)
  ;(let [[_ curie-prefix rest] (re-find #"^([a-zA-Z]+)[:_](.*)$" curie)]
  ;  (if curie-prefix
  ;    (if-let [iri-prefix (-> curie-prefix s/lower-case prefix-ns-map)]
  ;     (str iri-prefix rest)
  ;     r)))
  )

(def record-metadata-query-result
  {:name :RecordMetadataQueryResult
   :graphql-type :object
   :description "A struct for metadata query results"
   :fields {:iri {:type 'String
                  :description "IRI of the resource. May be slightly different than the IRI used in a query."
                  :resolve (fn [context args value] (str value))}
            :version {:type 'String
                      :description "Version information"
                      :path [:owl/version-info]}
            :is_version_of {:type 'String
                            :description "Unversioned IRI this record is a version of"
                            :path [:dc/is-version-of]}
            :has_versions {:type '(list String)
                           :description "Versions this record has. This is only a downward relation, does not represent lateral relations."
                           :path [[:dc/is-version-of :<]]}
            :replaces {:type 'String
                       :description "IRI of the record replaced by this record"
                       :path [:dc/replaces]}
            :replaced_by {:type 'String
                          :description "IRI of the record which replaces this record"
                          :path [:dc/is-replaced-by]}}})

(def record-metadata-query
  {:name :record_metadata_query
   :graphql-type :query
   :description "Find resources via common record-level metadata. Results can be used in follow-up queries to obtain details."
   :type :RecordMetadataQueryResult
   :args {:iri {:type 'String}}
   :resolve (fn [context args value]
              (q/resource (:iri args)))})

(defn pattern-query-resolver
  "Takes a pattern tuple in :pattern arg field. Returns resource IRIs which match this."
  [context args value]
  (let [{:keys [predicate object]} (:pattern args)
        spql (str "SELECT ?iri WHERE { ?iri ?pred ?obj }")]
    (log/debug :fn ::pattern-query-resolver :predicate predicate :object object)
    (q/select spql {:pred (q/resource predicate)
                    :obj (str (q/resource object))})))

(def pattern-tuple
  {:name :PatternTuple
   :graphql-type :input-object
   :description "A graph pattern tuple (predicate, object) to match IRIs"
   :fields {:predicate {:type 'String}
            :object {:type 'String}}})

(def pattern-query
  {:name :pattern_query
   :graphql-type :query
   :description "Find resources which match pattern"
   :type '(list :Resource)
   :args {:pattern {:type :PatternTuple}}
   :resolve pattern-query-resolver})

(def resource-query
  {:name :resource
   :graphql-type :query
   :description "Find a resource by IRI or CURIE"
   :args {:iri {:type 'String}}
   :type :Resource
   :resolve (fn [_ args _]
              (q/resource (:iri args)))})
