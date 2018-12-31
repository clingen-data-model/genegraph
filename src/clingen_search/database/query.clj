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
            QueryExecutionFactory]))

(defstate local-names
  :start (merge local-class-names local-property-names))

(defn- keyword-string-sub [[_ k-ns k]]
  (if-let [iri (some-> (keyword k-ns k) local-names .getURI)]
    (str "<" iri ">")
    (str ":" k-ns "/" k)))

(defn- expand-query-str [query-str]
  (s/replace query-str #":(\w+)/(\w+)" keyword-string-sub))

(defn select [query-str]
  (let [model (.getDefaultModel db)
        query (QueryFactory/create (expand-query-str query-str))]
    (tx
     (with-open [qexec (QueryExecutionFactory/create query model)]
       (when-let [result (-> qexec .execSelect)]
         (let [result-var (-> result .getResultVars first)
               result-seq (iterator-seq result)]
           (mapv #(.getResource % result-var) result-seq)))))))


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
