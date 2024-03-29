(ns genegraph.database.query
  (:require [genegraph.database.query.types :as types]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [genegraph.database.instance :refer [db]]
            [genegraph.database.names :as names]
            [genegraph.database.query.resource :as resource]
            [genegraph.database.util :as util :refer [tx]])
  (:import java.io.ByteArrayOutputStream
           org.apache.jena.graph.NodeFactory
           org.apache.jena.query.QueryFactory
           [org.apache.jena.rdf.model ModelFactory Model]
           org.apache.jena.sparql.algebra.Algebra))

(defn get-all-graphs []
  (or @util/current-union-model (.getUnionModel db)))

(defn curie
  "Return a curie string for resource. Return the IRI of the resource if no prefix has been defined"
  [resource]
  (names/curie (str resource)))

(defn resource?
  "Return true if the object is an RDFResource"
  [r]
  (types/resource? r))

(defn as-jena-resource
  "Return the underlying Jena Resource under the RDFResource"
  [r]
  (types/as-jena-resource r))

(defn is-rdf-type?
  "Return true if r is an instance of rdf-type"
  [r rdf-type]
  (types/is-rdf-type? r rdf-type))

(defn to-ref
  "Return the keyword associated with this resource (if any)"
  [r]
  (types/to-ref r))

(defn path 
  "Return the URL by which this resource can be addressed in the system

  DEPRECATED this responsibility should lie elsewhere"
  [r]
  (types/path r))

(defn select
  "Execute a SPARQL SELECT query (in string form). Will return a vector of results. Typical use is
  to return a list of RDFResources, with downstream properties interrogated by other methods"
  ([query-def] (resource/select query-def))
  ([query-def params] (resource/select query-def params))
  ([query-def params db-or-model] (resource/select query-def params db-or-model)))

(defn resource 
  "Create a resource reference defined by either a keyword (known to the system),
  or a string containing the IRI of an RDF Resource"
  [r]
  (types/resource r))

(defn ld->
  "Return the non-nil targets of resouce following key sequence of properties"
  [resource ks]
  (types/ld-> resource ks))

(defn ld1->
  "Return the first non-nil target of resouce following key sequence of properties"
  [resource ks]
  (types/ld1-> resource ks))

(defn construct 
  "Return a model built using a SPARQL CONSTRUCT query, accepts a SPARQL string,
  optionally parameter bindings and an origin model."
  ([query-string] (construct query-string {}))
  ([query-string params] (construct query-string {} (get-all-graphs)))
  ([query-string params model]
   (resource/construct query-string params model)))

(defn get-named-graph [name]
  (.getNamedModel db name))

(defn list-named-graphs []
   (into [] (iterator-seq (.listNames db))))

(defn to-turtle [model]
  (let [os (ByteArrayOutputStream.)]
    (.write model os "TURTLE")
    (.toString os)))

(defn to-binary [model]
  (let [os (ByteArrayOutputStream.)]
    (.write model os "RDFTHRIFT")
    (.toByteArray os)))

(defn to-jsonld [model]
  (let [os (ByteArrayOutputStream.)]
    (.write model os "JSONLD")
    (.toString os)))

(defn union
  "Create a new model that is the union of models"
  [& models]
  (let [union-model (ModelFactory/createDefaultModel)]
    (doseq [model models] (.add union-model model))
    union-model))

(defn empty-model []
  (ModelFactory/createDefaultModel))

(defn create-query 
  "Return parsed query object. If query is not a string, assume object that can
use io/slurp"
  ([query-source] (create-query query-source {}))
  ([query-source params]
   (resource/create-query query-source params)))

(defmacro declare-query [& queries]
  (let [root# (-> *ns* str (s/replace #"\." "/") (s/replace #"-" "_") (str "/"))]
    `(do ~@(map #(let [filename# (str root# (s/replace % #"-" "_" ) ".sparql")]
                   `(def ~% (-> ~filename# io/resource slurp create-query)))
                queries))))

(defn to-algebra [query]
  (-> query
      create-query
      str
      QueryFactory/create
      Algebra/compile
      println))

(defn text-search-bgp
  "Produce a BGP fragment for performing a text search based on a resource.
  Will produce a list of properties matching 'text', which may be either a
  property or a variable.

  A complete query using this function could be composed like this:
  (create-query [:project ['x] (cons :bgp (text-search-bgp 'x :cg/resource 'text))])

  where x is a resource to return, and text is a variable expected to be bound to the
  text to search for"
  [resource property text]
  (let [node0 (symbol "text0")
        node1 (symbol "text1")
        rdf-first (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#first")
        rdf-rest (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#rest")]
    [[resource (NodeFactory/createURI "http://jena.apache.org/text#query") node0]
     [node0 rdf-first property]
     [node0 rdf-rest node1]
     [node1 rdf-first text]
     [node1 rdf-rest
      (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#nil")]]))

(defn referenced-resources
  "Return the set of objects and subjects referenced by MODEL (excluding
  predicates). Useful for expiring cache objects when a model
  containing new objects is received."
  [model]
  (with-open [objects  (.listObjects  model)
              subjects (.listSubjects model)]
    (->> (concat (iterator-seq objects) (iterator-seq subjects))
         (filter #(and (.isResource %) (not (.isAnon %))))
         (map resource)
         set)))

(defn difference
  "Return the model representing the elements in MODEL-ONE not in MODEL-TWO"
  [model-one model-two]
  (.difference model-one model-two))

(defn is-isomorphic?
  "Return true if MODEL-ONE is isomorphic relative to MODEL-TWO"
  [model-one model-two]
  (.isIsomorphicWith model-one model-two))

(defn pp-model
  "Print a turtle-like string of model, with iri values
  substituted for local keywords when available."
  [model]
  (let [statements (iterator-seq (.listStatements model))
        predicate-iri-kw (map #(vector % (names/property-uri->keyword %))
                              (set (map #(.getPredicate %) statements)))
        object-iri-kw (map #(vector % (names/class-uri->keyword %))
                           (set (map #(.getObject %) statements)))]
    (println
     (reduce (fn [model-str [iri kw]]
               (s/replace model-str
                          (str "<" iri ">")
                          (str kw)))
             (to-turtle (.clearNsPrefixMap model))
             (filter second ; remove when no mapping exists
                     (concat predicate-iri-kw object-iri-kw))))))


(comment
  (genegraph.database.query/pp-model
   (genegraph.database.load/statements-to-model
    [["http://example.com/example" :rdf/type :rdfs/Class]
     ["http://example.com/example" :rdfs/label "My Class"]]))
  "  (genegraph.database.query/pp-model
   (genegraph.database.load/statements-to-model
    [["http://example.com/example" :rdf/type :rdfs/Class]
     ["http://example.com/example" :rdfs/label "My Class"]]))")
