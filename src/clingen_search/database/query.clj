(ns clingen-search.database.query
  (:require [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [tx]]
            [clingen-search.database.names :as names :refer
             [local-class-names local-property-names]]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.datafy :as d]
            [clojure.string :as s]
            [mount.core :refer [defstate]])
  (:import [org.apache.jena.rdf.model Model Statement ResourceFactory Resource Literal]
           [org.apache.jena.query QueryFactory Query QueryExecution
            QueryExecutionFactory QuerySolutionMap]))

(defstate local-names
  :start (merge local-class-names local-property-names))

(defonce query-register (atom {}))

(defn- keyword-string-sub [[_ k-ns k]]
  (if-let [iri (some-> (keyword k-ns k) local-names .getURI)]
    (str "<" iri ">")
    (str ":" k-ns "/" k)))>

(defn- expand-query-str [query-str]
  (s/replace query-str #":(\S+)/(\S+)" keyword-string-sub))

(defn register-query [name query-str]
  (let [q (QueryFactory/create (expand-query-str query-str))]
    (swap! query-register assoc name q)
    true))

(defprotocol AsClojureType
  (to-clj [x]))

(extend-protocol AsClojureType
  Resource
  (to-clj [x] (->RDFResource x))
  
  Literal
  (to-clj [x] (.getString x)))

(defprotocol Steppable
  (step [edge start model]))

(extend-protocol Steppable

  ;; Single keyword, treat as [:ns/prop :>] (outward pointing edge)
  clojure.lang.Keyword
  (step [edge start model]
    (step [edge :>] start model))
  
  ;; Expect edge to be a vector with form [:ns/prop <direction>], where direction is one
  ;; of :> :< :-
  clojure.lang.IPersistentVector
  (step [edge start model]
    (tx 
     (let [property (names/local-property-names (first edge))
           out-fn (fn [n] (->> (.listStatements model (.resource n) property nil)
                               iterator-seq (map #(.getObject %))))
           in-fn (fn [n] (->> (.listStatements model nil property (.resource n))
                              iterator-seq (map #(.getSubject %))))
           both-fn #(concat (out-fn %) (in-fn %))
           step-fn (case (second edge)
                     :> out-fn
                     :< in-fn
                     :- both-fn)
           result (mapv to-clj (step-fn start))]
       (case (count result)
         0 nil
         1 (first result)
         result)))))

(deftype RDFResource [resource]

  ;; TODO, returns all properties when k does not map to a known symbol,
  ;; This seems to break the contract for ILookup
  clojure.lang.ILookup
  (valAt [this k] (step k this (.getDefaultModel db)))
  (valAt [this k nf] nf)

  Object
  (toString [_] (.getURI resource)))

(defprotocol AsRDFNode
  (to-rdf-node [x]))

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

(defprotocol SelectQuery
  (select [query-def] [query-def params]))

(extend-protocol SelectQuery
  
  Query
  (select
    ([query-def] (select query-def {}))
    ([query-def params]
     (let [model (.getDefaultModel db)
           qs-map (construct-query-solution-map params)]
       (tx
        (with-open [qexec (QueryExecutionFactory/create query-def model qs-map)]
          (when-let [result (-> qexec .execSelect)]
            (let [result-var (-> result .getResultVars first)
                  result-seq (iterator-seq result)]
              (mapv #(->RDFResource (.getResource % result-var)) result-seq))))))))
  
  java.lang.String
  (select 
    ([query-def] (select query-def {}))
    ([query-def params] (select (QueryFactory/create (expand-query-str query-def)) params)))
  
  clojure.lang.Keyword
  (select
    ([query-def] (select query-def {}))
    ([query-def params]
     (if-let [q (@query-register query-def)]
       (select q params)
       #{}))))
