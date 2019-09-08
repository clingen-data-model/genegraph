(ns genegraph.database.query
  (:require [genegraph.database.instance :refer [db]]
            [genegraph.database.util :refer [tx]]
            [genegraph.database.names :as names :refer
             [local-class-names local-property-names
              class-uri->keyword local-names ns-prefix-map prefix-ns-map]]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.core.protocols :refer [Datafiable]]
            [clojure.datafy :refer [datafy]])
  (:import [org.apache.jena.rdf.model Model Statement ResourceFactory Resource Literal RDFList SimpleSelector]
           [org.apache.jena.query QueryFactory Query QueryExecution
            QueryExecutionFactory QuerySolutionMap]
           java.io.ByteArrayOutputStream))

(defprotocol Steppable
  (step [edge start model]))

(defprotocol AsReference
  (to-ref [resource]))

(defprotocol AsClojureType
  (to-clj [x model]))

(defprotocol AsRDFNode
  (to-rdf-node [x]))

(defprotocol SelectQuery
  (select [query-def] [query-def params] [query-def params model]))

(defprotocol AsResource
  "Create an RDFResource given a reference"
  (resource [r] [ns-prefix r]))

(defprotocol Addressable
  "Retrieves the local path to a resource"
  (path [this]))

(defprotocol ThreadableData
  "A data structure that can be accessed through the ld-> and ld1-> accessors
  similar to Clojure XML zippers"
  (ld-> [this ks])
  (ld1-> [this ks]))

(defprotocol RDFType
  (is-rdf-type? [this rdf-type]))

(defprotocol AsJenaResource
  (as-jena-resource [this]))

(deftype RDFResource [resource model]

  AsJenaResource
  (as-jena-resource [_] resource)

  RDFType
  (is-rdf-type? [this rdf-type] 
    (let [t (if (= (type rdf-type) clojure.lang.Keyword) 
              (local-names rdf-type)
              (ResourceFactory/createResource rdf-type))]
      (tx (.contains model resource (local-names :rdf/type) t))))

  ;; TODO, returns all properties when k does not map to a known symbol,
  ;; This seems to break the contract for ILookup
  clojure.lang.ILookup
  (valAt [this k] (step k this model))
  (valAt [this k nf] nf)

  Object
  (toString [_] (.getURI resource))
  ;; (equals [this o] (when (instance? RDFResource o)
  ;;                    (= (str resource) (str o))))
  ;; (hashCode [_] (-> resource .getURI .hashCode))
  
  AsReference
  (to-ref [_] (class-uri->keyword resource))

  Datafiable
  (datafy [_] (datafy resource))


  ;; TODO Flattening the ld-> has potentially undesirable behavior with RDFList, consider
  ;; how flatten is being used in this context
  ThreadableData
  (ld-> [this ks] (reduce (fn [nodes k]
                            (->> nodes
                                 (map #(step k % model))
                                 (filter seq) flatten))
                          [this] 
                          ks))
  (ld1-> [this ks] (first (ld-> this ks)))
  
  Addressable
  (path [_] (let [uri (.getURI resource)
                  short-ns (names/curie uri)
                  full-ns (prefix-ns-map short-ns)
                  id (subs uri (count full-ns))]
              (str "/r/" short-ns "_" id))))

(defonce query-register (atom {}))

(defn- substitute-known-iri-short-name [k-ns k]
  (when-let [iri (some-> (keyword k-ns k) local-names .getURI)]
    (str "<" iri ">")))

(defn- substitute-known-ns [k-ns k]
  (when-let [base (names/prefix-ns-map k-ns)]
    (str "<" base k ">")))

(defn- substitute-keyword [[_ k-ns k]]
  (let [kw (keyword k-ns k)]
    (cond (local-names kw) (str "<" (-> kw local-names .getURI) ">")
          (prefix-ns-map k-ns) (str "<" (prefix-ns-map k-ns) k ">")
          :else (str ":" k-ns "/" k))))

;; TODO fix so that non-whitespace terminated expressions are treated appropriately
(defn- expand-query-str [query-str]
  (s/replace query-str #":(\S+)/(\S+)" substitute-keyword))

(defn register-query [name query-str]
  (let [q (QueryFactory/create (expand-query-str query-str))]
    (swap! query-register assoc name q)
    true))


(def first-property (ResourceFactory/createProperty "http://www.w3.org/1999/02/22-rdf-syntax-ns#first"))

(defn rdf-list-to-vector [rdf-list-node model]

  (let [rdf-list (.as rdf-list-node RDFList)]
    (->> rdf-list .iterator iterator-seq (mapv #(to-clj % model)))
    ))

(extend-protocol AsClojureType

  Resource
  (to-clj [x model] (if (.hasProperty x first-property)
                      (rdf-list-to-vector x model)
                      (->RDFResource x model)))
  
  Literal
  (to-clj [x model] (.getValue x)))

(extend-protocol Steppable

  ;; Single keyword, treat as [:ns/prop :>] (outward pointing edge)
  clojure.lang.Keyword
  (step [edge start model]
    (step [edge :>] start model))
  
  ;; Expect edge to be a vector with form [:ns/prop <direction>], where direction is one
  ;; of :> :< :-
  ;; TODO fail more gracefully when starting point is a literal
  clojure.lang.IPersistentVector
  (step [edge start model]
    (tx 
     (let [property (names/local-property-names (first edge))
           out-fn (fn [n] (->> (.listObjectsOfProperty model (.resource n) property)
                               iterator-seq))
           in-fn (fn [n] (->> (.listResourcesWithProperty model property (.resource n))
                              iterator-seq))
           both-fn #(concat (out-fn %) (in-fn %))
           step-fn (case (second edge)
                     :> out-fn
                     :< in-fn
                     :- both-fn)
           result (mapv #(to-clj % model) (step-fn start))]
       (case (count result)
         0 nil
         ;; 1 (first result)
         result)))))

(extend-protocol AsRDFNode

  java.lang.String
  (to-rdf-node [x] (ResourceFactory/createPlainLiteral x))
  
  clojure.lang.Keyword
  (to-rdf-node [x] (local-names x)))

(defn- construct-query-solution-map [params]
  (let [qs-map (QuerySolutionMap.)]
    (doseq [[k v] params]
      (.add qs-map (name k) (to-rdf-node v)))
    qs-map))

(extend-protocol SelectQuery
  
  ;; TODO--consider removing this method into a function, do not want to expose
  ;; interfaces against Jena types
  Query
  (select
    ([query-def] (select query-def {}))
    ([query-def params] (select query-def params (.getUnionModel db)))
    ([query-def params model]
     (let [qs-map (construct-query-solution-map (dissoc params :-model))]
       (tx
        (with-open [qexec (QueryExecutionFactory/create query-def model qs-map)]
          (when-let [result (-> qexec .execSelect)]
            (let [result-var (-> result .getResultVars first)
                  result-seq (iterator-seq result)]
              (mapv #(->RDFResource (.getResource % result-var) model) result-seq))))))))
  
  java.lang.String
  (select 
    ([query-def] (select query-def {}))
    ([query-def params] (select query-def params (.getUnionModel db)))
    ([query-def params model] 
     (select (QueryFactory/create (expand-query-str query-def)) params model)))
  
  clojure.lang.Keyword
  (select
    ([query-def] (select query-def {}))
    ([query-def params] (select query-def params (.getUnionModel db)))
    ([query-def params model]
     (if-let [q (@query-register query-def)]
       (select q params model)
       #{}))))

(extend-protocol AsResource
  
  java.lang.String
  (resource 
    ([r] (->RDFResource (ResourceFactory/createResource r) (.getUnionModel db)))
    ([ns-prefix r] (when-let [prefix (prefix-ns-map ns-prefix)]
                     (->RDFResource (ResourceFactory/createResource (str prefix r))
                                    (.getUnionModel db)))))
  
  clojure.lang.Keyword
  (resource [r] (when-let [res (local-names r)]
                  (->RDFResource res (.getUnionModel db)))))

(defn get-named-graph [name]
  (.getNamedModel db name))

(defn get-all-graphs []
  (.getUnionModel db))

(defn to-turtle [model]
  (let [os (ByteArrayOutputStream.)]
    (.write model os "TURTLE")
    (.toString os)))