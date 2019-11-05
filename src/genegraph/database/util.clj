(ns genegraph.database.util
  "Utility functions used by other components of genegraph.database
  should not generally be used directly by code outside this namespace"
  (:require [genegraph.database.instance :refer [db]]
            [io.pedestal.log :as log])
  (:import [org.apache.jena.rdf.model ResourceFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.query ReadWrite QueryFactory QueryExecutionFactory]))

(defn property [uri]
 (ResourceFactory/createProperty uri))

(def ^:dynamic *in-tx* false)

(defmacro tx 
  "Open a read transaction on the persistent database. Most commands that read data from the databse will call this internally, since Jena TDB explicitly requires opening a transaction to read any data. If one wishes to issue multiple read commands within the scope of a single transaction, it is perfectly acceptable to wrap them all in a tx call, as this uses the var *in-tx* to ensure only a single transaction per thread is opened."
  [& body]
  `(if *in-tx*
     (do ~@body)
     (binding [*in-tx* true]
       (.begin db ReadWrite/READ)
       (try
         (let [result# (do ~@body)]
           result#)
         (catch Exception e# (log/error :fn :tx :msg e#) (.abort db))
         (finally (.end db))))))

(defmacro write-tx 
  "Open a write transaction on the persistent database. Will roll back in case an exception is raised, does not propagate the exception currently. Users will need to explicitly call .commit within the context of a transaction."
  [& body]
  `(if *in-tx*
     (do ~@body)
     (binding [*in-tx* true]
       (.begin db ReadWrite/WRITE)
       (try
         (let [result# (do ~@body)]
           result#)
         (catch Exception e# (log/error :fn :tx :msg e#) (.abort db))
         (finally (.end db))))))

(defmacro with-test-database 
  "Uses with-redefs to replace reference to production database with temporary,
  empty in-memory database. Useful for running unit tests."
  [& form]
  `(with-redefs [genegraph.database.instance/db (TDB2Factory/createDataset)]
     ~@form))

(defn select [query-str]
  (let [model (.getUnionModel db)
        query (QueryFactory/create query-str)]
    (with-open [qexec (QueryExecutionFactory/create query model)]
      (when-let [result (-> qexec .execSelect)]
        (let [result-var (-> result .getResultVars first)
              result-seq (iterator-seq result)
              literals (filter #(-> % (.get result-var) .isLiteral) result-seq )]
          (mapv #(.getResource % result-var) result-seq))))))
