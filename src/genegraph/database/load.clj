(ns genegraph.database.load
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [genegraph.database.instance :refer [db]]
            [genegraph.database.names :refer [local-property-names local-class-names]]
            [genegraph.database.property-store :as property-store]
            [genegraph.database.query :as q]
            [genegraph.database.util :refer [property tx write-tx]]
            [io.pedestal.log :as log]
            [mount.core :as mount :refer [defstate]])
  (:import [org.apache.jena.ontology OntResource]
           [org.apache.jena.query TxnType Dataset]
           [org.apache.jena.rdf.model Model ModelFactory Literal Resource
            ResourceFactory Statement]
           [org.apache.jena.tdb2 TDB2Factory]))

(def jena-rdf-format
  {:rdf-xml "RDF/XML"
   :json-ld "JSON-LD"
   :turtle "Turtle"})

(defn blank-node []
  (ResourceFactory/createResource))

(defn read-rdf
  ([src] (read-rdf src {}))
  ([src opts] (-> (ModelFactory/createDefaultModel)
                  (.read src nil (jena-rdf-format (:format opts :rdf-xml))))))

(defn store-rdf
  "Expects src to be compatible with Model.read(src, nil). A java.io.InputStream is
  likely the most appropriate type. :name parameter required in opts."
  ([src opts]
   (write-tx
    (let [in (-> (ModelFactory/createDefaultModel)
                 (.read src nil (jena-rdf-format (:format opts :rdf-xml))))]
      (.replaceNamedModel db (:name opts) in))
    true)))

(defn ^Statement construct-statement
  "Takes a [s p o] triple such as that used by genegraph.database.load/statements-to-model.
  Returns a single Statement."
  ([stmt] (construct-statement stmt {}))
  ([stmt opts]
   (try
     (let [[s p o] stmt
           subject (cond
                     (keyword? s) (local-class-names s)
                     (string? s) (ResourceFactory/createResource s)
                     (q/resource? s) (q/as-jena-resource s)
                     :else s)
           predicate (if (keyword? p)
                       (local-property-names p)
                       (ResourceFactory/createProperty p))
           object (cond
                    (keyword? o) (local-class-names o)
                    (= :Resource (:object (meta stmt))) (ResourceFactory/createResource o)
                    (or (string? o)
                        (int? o)
                        (float? o)) (ResourceFactory/createTypedLiteral o)
                    (q/resource? o) (q/as-jena-resource o)
                    :else o)]
       (ResourceFactory/createStatement subject predicate object))
     (catch Exception e
       (do (log/error :msg "Failed to create statement" :fn ::construct-statement :exception e :statement stmt)
           (throw e))))))

(defn statements-to-model
  [stmts]
  (let [m (ModelFactory/createDefaultModel)
        constructed-statements (into-array Statement (map construct-statement stmts))]
    (.add m constructed-statements)))

(defn load-model
  "Store model in the local, persistent database as named graph with name. Replace named graph if already present. Optionally, accepts an options map. At present the only option is :validate true/false. Defaults to false. If true, load-model will attempt to validate the model in the context of the local persistent data with whatever SHACL constraints are loaded in the database. If any constraints fail, will rollback the transaction, log an error, and return a structure signaling the failure, the reason, and a report with the result."
  ([model name]
   (load-model model name {}))
  ([model name opts]
   (write-tx
    (.replaceNamedModel db name model)
    ;; (property-store/put-model! model)
    {:succeeded true})))

(defn remove-model
  "Remove a named model from the database."
  [name]
  (write-tx
   (.removeNamedModel db name)
   {:succeded true}))

(defn load-statements
  "Statements are a three-item sequence. Will be imported as a named graph into TDB"
  ([stmts name]
   (load-model (statements-to-model stmts) name)))

(defn set-ns-prefixes [prefixes]
  (write-tx
   (let [m (.getUnionModel db)]
     (.clearNsPrefixMap m)
     (.setNsPrefixes m prefixes)
     true)))
