(ns genegraph.database.load
  (:require [mount.core :as mount :refer [defstate]]
            [clojure.pprint :refer [pprint]]
            [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [genegraph.database.instance :refer [db]]
            [genegraph.database.util :refer [property tx write-tx]]
            [genegraph.database.names :refer [local-property-names local-class-names]]
            [genegraph.database.query :as q]
            [genegraph.database.validation :as v]
            [io.pedestal.log :as log])
  (:import [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.query TxnType Dataset]
           [org.apache.jena.rdf.model Model ModelFactory Literal Resource ResourceFactory
            Statement]
           [org.apache.jena.ontology OntResource]))

(def jena-rdf-format
  {:rdf-xml "RDF/XML"
   :json-ld "JSON-LD"
   :turtle "Turtle"})

(defn blank-node []
  (ResourceFactory/createResource))

(defn read-rdf
  ([src] (read-rdf src {}))
  ([src opts] (try
                (-> (ModelFactory/createDefaultModel)
                    (.read src nil (jena-rdf-format (:format opts :rdf-xml))))
                (catch Exception e (log/error :fn :read-rdf :src src :msg (.message e)))))) 

(defn store-rdf 
  "Expects src to be compatible with Model.read(src, nil). A java.io.InputStream is
  likely the most appropriate type. :name parameter required in opts."
  ([src opts]
   (write-tx
    (let [in (-> (ModelFactory/createDefaultModel)
                 (.read src nil (jena-rdf-format (:format opts :rdf-xml))))]
      (.replaceNamedModel db (:name opts) in))
    true)))

(defn- construct-statement 
  ([stmt] (construct-statement stmt {}))
  ([stmt opts]
   (let [[s p o] stmt
         subject (cond
                   (keyword? s) (local-class-names s)
                   (string? s) (ResourceFactory/createResource s)
                   (satisfies? q/AsJenaResource s) (q/as-jena-resource s)
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
                  (satisfies? q/AsJenaResource o) (q/as-jena-resource o)
                  :else o)]
     (ResourceFactory/createStatement subject predicate object))))

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
   (try
     (write-tx
      (.replaceNamedModel db name model)
      (.commit db)
      {:succeeded true})
     (catch Exception e# (log/error :fn :load-model :msg e#)))))

(defn remove-model
  "Remove a named model from the database."
  [name]
  (try
    (write-tx
     (.removeNamedModel db name)
     (.commit db)
     {:succeded true})
    (catch Exception e# (log/error :fn :remove-model :msg e#))))

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
