(ns genegraph.database.query.resource
  (:require [genegraph.database.instance :refer [db]]
            [genegraph.database.util :as util :refer [tx]]
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
            [medley.core :as medley]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy])
  (:import [org.apache.jena.rdf.model Model Statement ResourceFactory Resource Literal RDFList SimpleSelector ModelFactory]
           [org.apache.jena.query Dataset QueryFactory Query QueryExecution
            QueryExecutionFactory QuerySolutionMap]
           [org.apache.jena.sparql.algebra AlgebraGenerator Algebra OpAsQuery Op]
           [org.apache.jena.graph Node NodeFactory Triple Node_Variable Node_Blank]
[org.apache.jena.sparql.algebra.op OpDistinct OpProject OpFilter OpBGP OpConditional OpDatasetNames OpDiff OpDisjunction OpDistinctReduced OpExtend OpGraph OpGroup OpJoin OpLabel OpLeftJoin OpList OpMinus OpNull OpOrder OpQuad OpQuadBlock OpQuadPattern OpReduced OpSequence OpSlice OpTopN OpUnion OpTable ]
[org.apache.jena.sparql.core BasicPattern Var VarExprList QuadPattern Quad]
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

(defn get-all-graphs []
  (or @util/current-union-model (.getUnionModel db)))

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

(def query-sort-order 
  {:ASC Query/ORDER_ASCENDING
   :asc Query/ORDER_ASCENDING
   :DESC Query/ORDER_DESCENDING
   :desc Query/ORDER_DESCENDING})

(defn construct-query-with-params [query query-params]
  (if-let [params (:genegraph.database.query/params query-params)]
    (let [modified-query (.clone query)]
      (when (:distinct params)
        (.setDistinct modified-query true))
      (when (:limit params)
        (.setLimit modified-query (:limit params)))
      (when (:offset params)
        (.setOffset modified-query (:offset params)))
      (when (:sort params)
        (let [{:keys [field direction]} (:sort params)]
          (.addResultVar modified-query (s/lower-case (name field)))
          (.addOrderBy modified-query (s/lower-case (name field)) (query-sort-order direction))))
      modified-query)
    query))

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
  (equals [this other]
    (and (satisfies? AsJenaResource other)
         (= resource (as-jena-resource other))))
  (hashCode [_] (.hashCode resource))
  

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
                            {:genegraph.database.query/value  (.getObject %)})
                         out-attributes)
                   (mapv #(with-meta [[(-> % .getPredicate property-uri->keyword) :<]
                                      (-> % .getSubject compose-object-for-datafy)]
                            {:genegraph.database.query/value (.getSubject %)})
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

(nippy/extend-freeze 
 RDFResource ::rdf-resource
 [x data-output]
 (.writeUTF data-output (str x)))

(nippy/extend-thaw 
 ::rdf-resource
 [data-input]
 (when-let [resource-iri (.readUTF data-input)]
   (resource resource-iri)))

(defn- navize [model]
  (fn [coll k v]
    (let [target (:genegraph.database.query/value (meta v))]
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
     (let [query (construct-query-with-params query-def params)
           model-from-params (:genegraph.database.query/model params)
           qs-map (construct-query-solution-map (dissoc params :genegraph.database.query/model :genegraph.database.query/params))]
       (tx
        (with-open [qexec (QueryExecutionFactory/create query db-or-model qs-map)]
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
    ([query-def params] (select query-def params (or (:genegraph.database.query/model params) db)))
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
    ([r] (let [[_ curie-prefix rest] (re-find #"^([a-zA-Z]+)[:_](.*)$" r)
               iri (if-let [iri-prefix (-> curie-prefix s/lower-case prefix-ns-map)]
                     (str iri-prefix rest)
                     r)]
           (->RDFResource (ResourceFactory/createResource iri) (get-all-graphs))))
    ([ns-prefix r] (when-let [prefix (prefix-ns-map ns-prefix)]
                     (->RDFResource (ResourceFactory/createResource (str prefix r))
                                    (get-all-graphs)))))
  
  clojure.lang.Keyword
  (resource [r] (when-let [res (local-names r)]
                  (->RDFResource res (get-all-graphs)))))

(extend-protocol AsClojureType

  Resource
  (to-clj [x model] (if (.hasProperty x first-property)
                      (rdf-list-to-vector x model)
                      (->RDFResource x model)))
  
  Literal
  (to-clj [x model] (.getValue x)))

(defn construct 
  ([query-string] (construct query-string {}))
  ([query-string params] (construct query-string {} (get-all-graphs)))
  ([query-string params model]
   (let [query (QueryFactory/create (expand-query-str query-string))
         qs-map (construct-query-solution-map (dissoc params :-model))]
     (tx
      (with-open [qexec (QueryExecutionFactory/create query model qs-map)]
        (.execConstruct qexec))))))

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


;;;; Query builder

(defn text-index [var ])

(defn triple
  "Construct triple for use in BGP. Part of query algebra."
  [stmt]
  (let [[s p o] stmt
        subject (cond
                  (instance? Node s) s
                  (= '_ s) Var/ANON
                  (symbol? s) (Var/alloc (str s))
                  (keyword? s) (.asNode (local-class-names s))
                  (string? s) (NodeFactory/createURI s)
                  (satisfies? AsJenaResource s) (.asNode (as-jena-resource s))
                  :else (NodeFactory/createURI (str s)))
        predicate (cond
                    (instance? Node p) p
                    (keyword? p) (.asNode (local-property-names p))
                    :else (NodeFactory/createURI (str p)))
        object (cond 
                 (instance? Node o) o
                 (symbol? o) (Var/alloc (str o))
                 (keyword? o) (.asNode (or (local-class-names o) (local-property-names o)))
                 (or (string? o)
                     (int? o)
                     (float? o)) (.asNode (ResourceFactory/createTypedLiteral o))
                 (satisfies? AsJenaResource o) (.asNode (as-jena-resource o))
                 :else o)]
    (Triple. subject predicate object)))

;; (defn expr
;;   "Convert a Clojure data structure to an Arq Expr"
;;   [expr]
;;   (if (instance? java.util.List expr)
;;     (composite-expr expr)
;;     (let [node (graph/node expr)]
;;       (if (instance? Node_Variable node)
;;         (ExprVar. (Var/alloc ^Node_Variable node))
;;         (NodeValue/makeNode node)))))

;; (defn- var-expr-list
;;   "Given a vector of var/expr bindings (reminiscient of Clojure's `let`), return a Jena VarExprList with vars and exprs."
;;   [bindings]
;;   (let [vel (VarExprList.)]
;;     (doseq [[v e] (partition 2 bindings)]
;;       (.add vel (Var/alloc (graph/node v))
;;         (expr e)))
;;     vel))

;; (defn- var-aggr-list
;;   "Given a vector of var/aggregate bindings return a Jena VarExprList with
;;    vars and aggregates"
;;   [bindings]
;;   (vec (for [[v e] (partition 2 bindings)]
;;          (ExprAggregator. (Var/alloc (graph/node v)) (aggregator e)))))

;; (defn- sort-conditions
;;   "Given a seq of expressions and the keyword :asc or :desc, return a list of
;;    sort conditions."
;;   [conditions]
;;   (for [[e dir] (partition 2 conditions)]
;;     (SortCondition. ^Expr (expr e) (if (= :asc dir) 1 -1))))

(defn- var-seq [vars]
  (map #(Var/alloc (str %)) vars))

(declare op)

(defn- op-union [a1 a2 & amore]
  (OpUnion. 
   (op a1)
   (if amore
     (apply op-union a2 amore)
     (op a2))))

(defn op
  "Convert a Clojure data structure to an Arq Op"
  [[op-name & [a1 a2 & amore :as args]]]
  (case op-name
    :distinct (OpDistinct/create (op a1))
    :project (OpProject. (op a2) (var-seq a1))
    ;; :filter (OpFilter/filterBy (ExprList. ^List (map expr (butlast args))) (op (last args)))
    :bgp (OpBGP. (BasicPattern/wrap (map triple args)))
    :conditional (OpConditional. (op a1) (op a2))
    :diff (OpDiff/create (op a1) (op a2))
    :disjunction (OpDisjunction/create (op a1) (op a2))
    ;; :extend (OpExtend/create (op a2) (var-expr-list a1))
    ;; :group (OpGroup/create (op (first amore))
    ;;                        (VarExprList. ^List (var-seq a1))
    ;;                        (var-aggr-list a2))
    :join (OpJoin/create (op a1) (op a2))
    :label (OpLabel/create a1 (op a2))
    ;; :left-join (OpLeftJoin/create (op a1) (op a2) (ExprList. ^List (map expr amore)))
    :list (OpList. (op a1))
    :minus (OpMinus/create (op a1) (op a2))
    :null (OpNull/create)
    ;; :order (OpOrder. (op a2) (sort-conditions a1))
    :reduced (OpReduced/create (op a1))
    :sequence (OpSequence/create (op a1) (op a2))
    :slice (OpSlice. (op a1) (long a1) (long (first amore)))
    ;; :top-n (OpTopN. (op (first amore)) (long a1) (sort-conditions a2))
    :union (apply op-union args)
    (throw (ex-info (str "Unknown operation " op-name) {:op-name op-name
                                                        :args args}))))


(defn- compose-select-result [qexec model]
  (when-let [result (-> qexec .execSelect)]
    (let [result-var (-> result .getResultVars first)
          result-seq (iterator-seq result)
          node-model (if (instance? Dataset model)
                       (get-all-graphs)
                       model)]
      (mapv #(->RDFResource (.getResource % result-var) node-model) result-seq))))

(defn- exec [query-def params]
  (let [qs-map (construct-query-solution-map (medley/filter-keys #(nil? (namespace %)) params))
        model (or (:genegraph.database.query/model params) db)
        result-model (or (:genegraph.database.query/model params) (get-all-graphs))
        query (construct-query-with-params query-def params)]
    (tx
     (with-open [qexec (QueryExecutionFactory/create query model qs-map)]
       (cond 
         (.isConstructType query) (.execConstruct qexec)
         (.isSelectType query) (if (= :count (get-in params [:genegraph.database.query/params :type]))
                                 (-> qexec .execSelect iterator-seq count)
                                 (compose-select-result qexec result-model))
         (.isAskType query) (.execAsk qexec))))))

(deftype StoredQuery [query]
  clojure.lang.IFn
  (invoke [this] (this {}))
  (invoke [this params] (exec query params))
  
  Object
  (toString [_] (str query)))

(defn create-query 
  "Return parsed query object. If query is not a string, assume object that can
use io/slurp"
  ([query-source] (create-query query-source {}))
  ([query-source params]
   (let [query (if  (coll? query-source)
                  (OpAsQuery/asQuery (op query-source))
                  (QueryFactory/create (expand-query-str
                                        (if (string? query-source)
                                          query-source
                                          (slurp query-source)))))]
     (case (:genegraph.database.query/type params)
       :ask (.setQueryAskType query)
       (.setDistinct query true))
     (->StoredQuery query))))

;; (defmacro declare-query [& queries]
;;   (let [root# (-> *ns* str (s/replace #"\." "/") (s/replace #"-" "_") (str "/"))]
;;     `(do ~@(map #(let [filename# (str root# (s/replace % #"-" "_" ) ".sparql")]
;;                    `(def ~% (-> ~filename# io/resource slurp create-query)))
;;                 queries))))

;; (defn to-algebra [query]
;;   (-> query
;;       create-query
;;       str
;;       QueryFactory/create
;;       Algebra/compile
;;       println))

;; (defn text-search-bgp
;;   "Produce a BGP fragment for performing a text search based on a resource.
;;   Will produce a list of properties matching 'text', which may be either a
;;   property or a variable.

;;   A complete query using this function could be composed like this:
;;   (create-query [:project ['x] (cons :bgp (text-search-bgp 'x :cg/resource 'text))])

;;   where x is a resource to return, and text is a variable expected to be bound to the
;;   text to search for"
;;   [resource property text]
;;   (let [node0 (symbol "text0")
;;         node1 (symbol "text1")
;;         rdf-first (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#first")
;;         rdf-rest (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#rest")]
;;     [[resource (NodeFactory/createURI "http://jena.apache.org/text#query") node0]
;;      [node0 rdf-first property]
;;      [node0 rdf-rest node1]
;;      [node1 rdf-first text]
;;      [node1 rdf-rest
;;       (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#nil")]]))

