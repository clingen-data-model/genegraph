(ns genegraph.database.util
  "Utility functions used by other components of genegraph.database
  should not generally be used directly by code outside this namespace"
  (:require [genegraph.database.instance :refer [db]]
            [io.pedestal.log :as log])
  (:import [org.apache.jena.rdf.model ResourceFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.query ReadWrite QueryFactory QueryExecutionFactory Dataset]))

(defn property [uri]
 (ResourceFactory/createProperty uri))

(def current-union-model (atom nil))

(defmacro tx 
  "Open a read transaction on the persistent database. Most commands that read data from the databse will call this internally, since Jena TDB explicitly requires opening a transaction to read any data. If one wishes to issue multiple read commands within the scope of a single transaction, it is perfectly acceptable to wrap them all in a tx call, as this uses the var *in-tx* to ensure only a single transaction per thread is opened."
  [& body]
  `(if (.isInTransaction db) 
     (do ~@body)
     (do
       (.begin db ReadWrite/READ)
       (reset! current-union-model (.getUnionModel db))
       (try
         (let [result# (do ~@body)]
           result#)
         (catch Exception e# (log/error :fn :tx :msg e#) (.abort db))
         (finally (.end db))))))

(defmacro write-tx 
  "Open a write transaction on the persistent database. Will roll back in case an exception is raised, does not propagate the exception currently. Users will need to explicitly call .commit within the context of a transaction."
  [& body]
  `(if (.isInTransaction db)
     (do ~@body)
     (do
       (.begin db ReadWrite/WRITE)
       (reset! current-union-model (.getUnionModel db))
       (try
         (let [result# (do ~@body)]
           result#)
         (catch Exception e# (log/error :fn :tx :msg e#) (.abort db))
         (finally (.end db))))))

(defn begin-read-tx
  "Open a read transaction on the persistent database and leave it open within the context of the current thread. When possible, the macro form is preferred, however this fn is available when one does not have access to the block of code to be called in the context of a transaction (i.e. in a Pedestal interceptor"
  []
  (when-not (.isInTransaction db)
    (.begin db ReadWrite/READ)
    (reset! current-union-model (.getUnionModel db))
    true))

(defn close-read-tx
  "Close a transaction opened with begin-read-tx"
  []
  (.end db))

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
