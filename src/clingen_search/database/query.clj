(ns clingen-search.database.query
  (:require [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [tx]]
            [clingen-search.database.names :as names :refer
             [local-class-names local-property-names]]
            [clingen-search.database.walk :refer [walk]]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.datafy :as d]
            [clojure.string :as s]
            [mount.core :refer [defstate]])
  (:import [org.apache.jena.rdf.model Model Statement ResourceFactory Resource]
           [org.apache.jena.query QueryFactory Query QueryExecution
            QueryExecutionFactory QuerySolutionMap]))

(defstate local-names
  :start (merge local-class-names local-property-names))

(defonce query-register (atom {}))

(defn- expand-query-str [query-str]
  (s/replace query-str #":(\S+)/(\S+)" keyword-string-sub))

(defn register-query [name query-str]
  (let [q (QueryFactory/create (expand-query-str query-str))]
    (swap! query-register assoc name q)
    true))

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
     (let [model (.getDefaultModel db)]
      (tx
       (with-open [qexec (QueryExecutionFactory/create query-def model)]
         (when-let [result (-> qexec .execSelect)]
           (let [result-var (-> result .getResultVars first)
                 result-seq (iterator-seq result)]
             (mapv #(.getResource % result-var) result-seq))))))))
  
  java.lang.String
  (select 
    ([query-def] (select query-def {}))
    ([query-def params] (select (QueryFactory/create (expand-query-str query-def)))))
  
  clojure.lang.Keyword
  (select
    ([query-def] (select query-def {}))
    ([query-def params]
     (if-let [q (@query-register query-def)]
       (select q params)
       #{}))))



(defn- keyword-string-sub [[_ k-ns k]]
  (if-let [iri (some-> (keyword k-ns k) local-names .getURI)]
    (str "<" iri ">")
    (str ":" k-ns "/" k)))

(defn properties [resource]
  (tx 
   (let [model (.getDefaultModel db)
         resource (if (string? resource) (ResourceFactory/createResource resource)
                      resource)
         attributes (-> model (.listStatements resource nil nil) iterator-seq)]
     (into {} (map 
               #(vector (d/datafy (.getPredicate %)) 
                        (d/datafy (.getObject %))) attributes)))))


;; to delete, used only to identify properties not defined in import rdf
(defn get-all-properties []
  (let [model (.getDefaultModel db)]
    (tx
     (let [stmts (iterator-seq (.listStatements model))]
       (into #{} (map #(.getPredicate %) stmts))))))
