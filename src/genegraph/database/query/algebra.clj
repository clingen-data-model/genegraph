(ns genegraph.database.query.algebra
    (:require [genegraph.database.instance :refer [db]]
              [genegraph.database.query.types :as types]
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
             org.apache.jena.sparql.core.Prologue
             java.io.ByteArrayOutputStream))

(defn- var-seq [vars]
  (map #(Var/alloc (str %)) vars))

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
                  (types/resource? o) (.asNode (types/as-jena-resource s))
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
                 (types/resource? o) (.asNode (types/as-jena-resource o))
                 :else o)]
    (Triple. subject predicate object)))

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
