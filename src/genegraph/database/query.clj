(ns genegraph.database.query
  (:require [genegraph.database.query.types :as types]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [genegraph.database.instance :refer [db]]
            [genegraph.database.names :as names]
            [genegraph.database.query.resource :as resource]
            [genegraph.database.util :as util])
  (:import java.io.ByteArrayOutputStream
           org.apache.jena.graph.NodeFactory
           org.apache.jena.query.QueryFactory
           org.apache.jena.rdf.model.ModelFactory
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
  (resource/as-jena-resource r))

(defn is-rdf-type?
  "Return true if r is an instance of rdf-type"
  [r rdf-type]
  (resource/is-rdf-type? r rdf-type))

(defn to-ref
  "Return the keyword associated with this resource (if any)"
  [r]
  (resource/to-ref r))

(defn path 
  "Return the URL by which this resource can be addressed in the system

  DEPRECATED this responsibility should lie elsewhere"
  [r]
  (resource/path r))

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
  (resource/resource r))

(defn ld->
  "Return the non-nil targets of resouce following key sequence of properties"
  [resource ks]
  (resource/ld-> resource ks))

(defn ld1->
  "Return the first non-nil target of resouce following key sequence of properties"
  [resource ks]
  (resource/ld1-> resource ks))

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

(defn union [& models]
  (let [union-model (ModelFactory/createDefaultModel)]
    (doseq [model models] (.add union-model model))
    union-model))

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
