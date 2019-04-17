(ns clingen-search.database.util
  "Utility functions used by other components of clingen-search.database
  should not generally be used directly by code outside this namespace"
  (:require [clingen-search.database.instance :refer [db]])
  (:import [org.apache.jena.rdf.model ResourceFactory]
           [org.apache.jena.query ReadWrite QueryFactory QueryExecutionFactory]))

(defn property [uri]
 (ResourceFactory/createProperty uri))

(def ^:dynamic *in-tx* false)

(defmacro tx [& body]
  `(if *in-tx*
     (do ~@body)
     (binding [*in-tx* true]
       (.begin db)
       (try
         (let [result# (do ~@body)]
           (.commit db)
           result#)
         (catch Exception e# (println "Exception: " e#) (.abort db))
         (finally (.end db))))))

(defmacro write-tx [& body]
  `(do
     (.begin db ReadWrite/WRITE)
     (try
       (let [result# (do ~@body)]
         (.commit db)
         result#)
       (catch Exception e# (println "Exception: " e#) (.abort db))
       (finally (.end db)))))

(defn select [query-str]
  (let [model (.getUnionModel db)
        query (QueryFactory/create query-str)]
    (with-open [qexec (QueryExecutionFactory/create query model)]
      (when-let [result (-> qexec .execSelect)]
        (let [result-var (-> result .getResultVars first)
              result-seq (iterator-seq result)
              literals (filter #(-> % (.get result-var) .isLiteral) result-seq )]
          (println literals)
          (mapv #(.getResource % result-var) result-seq))))))
