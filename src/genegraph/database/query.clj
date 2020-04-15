(ns genegraph.database.query
  (:require [genegraph.database.instance :refer [db]]
            [genegraph.database.util :refer [tx]]
            [genegraph.database.names :as names :refer
             [local-class-names local-property-names
              class-uri->keyword local-names ns-prefix-map
              prefix-ns-map property-uri->keyword]]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.core.protocols :as protocols :refer [Datafiable]]
            [clojure.datafy :as datafy :refer [datafy nav]]
            [io.pedestal.log :as log]
            [clojure.java.io :as io])
  (:import [org.apache.jena.rdf.model Model Statement ResourceFactory Resource Literal RDFList SimpleSelector ModelFactory]
           [org.apache.jena.query Dataset QueryFactory Query QueryExecution
            QueryExecutionFactory QuerySolutionMap]
           org.apache.jena.riot.writer.JsonLDWriter
           org.apache.jena.sparql.core.Prologue
           org.apache.jena.riot.RDFFormat$JSONLDVariant
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

(declare datafy-resource)


(defn curie
  "Return a curie string for resource. Return the IRI of the resource if no prefix has been defined"
  [resource]
  (names/curie (str resource)))

(defn- compose-object-for-datafy [o]
  (cond (instance? Literal o) (.toString o)
        (instance? Resource o) 
        (with-meta (-> o .toString symbol)
          {::datafy/obj o
           ::datafy/class (class o)
           `protocols/datafy #(-> % meta ::datafy/obj datafy-resource)})))

(declare navize)

(declare to-clj)

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


  ;; Conforms to the expectations for a sequence representation of a map. Includes only
  ;; properties where this resource is the subject. Sequence is fully realized
  ;; in order to permit access outside transaction
  clojure.lang.Seqable
  (seq [this]
    (tx
     (let [out-attributes (-> model (.listStatements resource nil nil) iterator-seq)]
       (doall (map #(vector 
                     (-> % .getPredicate property-uri->keyword)
                     (to-clj (.getObject %) model)) out-attributes)))))

  Object
  (toString [_] (.getURI resource))

  AsReference
  (to-ref [_] (class-uri->keyword resource))

  Datafiable
  (datafy [_] 
    (tx 
     (let [out-attributes (-> model (.listStatements resource nil nil) iterator-seq)
           in-attributes (-> model (.listStatements nil nil resource) iterator-seq)]

       (with-meta
         (into [] (concat
                   (mapv #(with-meta [[(-> % .getPredicate property-uri->keyword) :>]
                                      (-> % .getObject compose-object-for-datafy)]
                            {::value  (.getObject %)})
                         out-attributes)
                   (mapv #(with-meta [[(-> % .getPredicate property-uri->keyword) :<]
                                      (-> % .getSubject compose-object-for-datafy)]
                            {::value (.getSubject %)})
                         in-attributes)))
         {`protocols/nav (navize model)}))))

  ;; TODO Flattening the ld-> has potentially undesirable behavior with RDFList, consider
  ;; how flatten is being used in this context
  ThreadableData
  (ld-> [this ks] (reduce (fn [nodes k]
                            (->> nodes
                                 (filter #(satisfies? ThreadableData %))
                                 (map #(step k % model))
                                 (filter seq) flatten))
                          [this] 
                          ks))
  (ld1-> [this ks] (first (ld-> this ks)))
  
  ;; TODO Root path is hardcoded in--this should be configurable
  Addressable
  (path [_] (let [uri (.getURI resource)
                  short-ns (names/curie uri)
                  full-ns (prefix-ns-map short-ns)
                  id (subs uri (count full-ns))]
              (str "/r/" short-ns "_" id))))

(defn- navize [model]
  (fn [coll k v]
    (let [target (::value (meta v))]
      (if (instance? Resource target)
        (->RDFResource target model)
        target))))

;; deprecated--to be removed
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

(defn register-query 
  "DEPRECATED -- to be replaced with a different approach to stored queries"
  [name query-str]
  (let [q (QueryFactory/create (expand-query-str query-str))]
    (swap! query-register assoc name q)
    true))



(def first-property (ResourceFactory/createProperty "http://www.w3.org/1999/02/22-rdf-syntax-ns#first"))

(defn rdf-list-to-vector [rdf-list-node model]

  (let [rdf-list (.as rdf-list-node RDFList)]
    (->> rdf-list .iterator iterator-seq (mapv #(to-clj % model)))
    ))


(defn- kw-to-property [kw]
  (if-let [prop (names/local-property-names kw)]
    prop
    (when-let [ns (-> kw namespace prefix-ns-map)]
      (ResourceFactory/createProperty (str ns (name kw))))))

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
     (let [property (kw-to-property (first edge))
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
  (to-rdf-node [x] (local-names x))
  
  RDFResource
  (to-rdf-node [x] (as-jena-resource x)))

(defn- construct-query-solution-map [params]
  (let [qs-map (QuerySolutionMap.)]
    (doseq [[k v] params]
      (.add qs-map (name k) (to-rdf-node v)))
    qs-map))

(extend-protocol SelectQuery
  
  ;; TODO--consider removing this method into a function, do not want to expose
  ;; interfaces against Jena types
  ;;
  ;; The whole db-or-model ugliness below is due to an issue where QueryExecutionFactory/create
  ;; has the same method signature where the second param is either a Dataset or a Model.
  ;; If it is a Dataset that supports text indexing, we need to use the Dataset form otherwise
  ;; TextIndexPF complains about not finding a text index if we are doing a text search.
  ;; Also, the Dataset form needs to call .getUnionModel when resolving to Resources.
  ;; If we are querying (non-text) against a plain old Model then there is no .getUnionModel
  ;; call supported.
  ;;
  Query
  (select
    ([query-def] (select query-def {}))
    ([query-def params] (select query-def params db))
    ([query-def params db-or-model]
     (log/info :fn :select-query
                :msg "Executing select query"
                :query query-def
                :params params)
     (let [qs-map (construct-query-solution-map (dissoc params :-model))]
       (.setPrefixMapping query-def model)
       (tx
        (with-open [qexec (QueryExecutionFactory/create query-def db-or-model qs-map)]
          (when-let [result (-> qexec .execSelect)]
            (let [result-var (-> result .getResultVars first)
                  result-seq (iterator-seq result)
                  ;; TODO - needs to be refactored to not use type
                  model (if (instance? org.apache.jena.query.Dataset db-or-model)
                          (.getUnionModel db-or-model)
                          db-or-model)]
              (mapv #(->RDFResource (.getResource % result-var) model) result-seq))))))))
  
  java.lang.String
  (select 
    ([query-def] (select query-def {}))
    ([query-def params] (select query-def params db))
    ([query-def params db-or-model]
     (select (QueryFactory/create (expand-query-str query-def)) params db-or-model)))
  
  clojure.lang.Keyword
  (select
    ([query-def] (select query-def {}))
    ([query-def params] (select query-def params db))
    ([query-def params db-or-model]
     (if-let [q (@query-register query-def)]
       (select q params db-or-model)
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

(extend-protocol AsClojureType

  Resource
  (to-clj [x model] (if (.hasProperty x first-property)
                      (rdf-list-to-vector x model)
                      (->RDFResource x model)))
  
  Literal
  (to-clj [x model] (.getValue x)))

(defn construct 
  ([query-string] (construct query-string {}))
  ([query-string params] (construct query-string {} (.getUnionModel db)))
  ([query-string params model]
   (let [query (QueryFactory/create (expand-query-str query-string))
         qs-map (construct-query-solution-map (dissoc params :-model))]
     (tx
      (with-open [qexec (QueryExecutionFactory/create query model qs-map)]
        (.execConstruct qexec))))))

(defn get-named-graph [name]
  (.getNamedModel db name))

(defn get-all-graphs []
  (.getUnionModel db))

(defn to-turtle [model]
  (let [os (ByteArrayOutputStream.)]
    (.write model os "TURTLE")
    (.toString os)))

(defn- compose-select-result [qexec model]
  (when-let [result (-> qexec .execSelect)]
    (let [result-var (-> result .getResultVars first)
          result-seq (iterator-seq result)]
      (mapv #(->RDFResource (.getResource % result-var) model) result-seq))))

(defn- exec [query params]
  (let [qs-map (construct-query-solution-map (dissoc params ::model))
        model (or (::model params) (.getUnionModel db))]
    (tx
     (with-open [qexec (QueryExecutionFactory/create query model qs-map)]
       (cond 
         (.isConstructType query) (.execConstruct qexec)
         (.isSelectType query) (compose-select-result qexec model))))))

(deftype StoredQuery [query]
  clojure.lang.IFn
  (invoke [this] (this {}))
  (invoke [this params] (exec query params)))

(defn create-query 
  "Return parsed query object. If query is not a string, assume object that can
use io/slurp"
  [query-source]
  (let [query-str (if (string? query-source) 
                    query-source
                    (slurp query-source))]
    (->StoredQuery (QueryFactory/create (expand-query-str query-str)))))

(defmacro declare-query [& queries]
  (let [root# (-> *ns* str (s/replace #"\." "/") (s/replace #"-" "_") (str "/"))]
    `(do ~@(map #(let [filename# (str root# (s/replace % #"-" "_" ) ".sparql")]
                   `(def ~% (-> ~filename# io/resource slurp create-query)))
                queries))))

(defn union [& models]
  (let [union-model (ModelFactory/createDefaultModel)]
    (doseq [model models] (.add union-model model))
    union-model))
