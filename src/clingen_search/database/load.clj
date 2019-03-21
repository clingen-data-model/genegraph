(ns clingen-search.database.load
  (:require [mount.core :as mount :refer [defstate]]
            [clojure.pprint :refer [pprint]]
            [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [property tx write-tx]])
  (:import [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.query TxnType Dataset]
           [org.apache.jena.rdf.model Model ModelFactory Literal Resource ResourceFactory
            Statement]
           [org.apache.jena.ontology OntResource]))

(def jena-rdf-format
  {:rdf-xml "RDF/XML"
   :json-ld "JSON-LD"
   :turtle "Turtle"})

(defn get-model 
  "Get the named model in the triplestore instance. If graph-name is nil, or if the 
  function is called with no arguments, return the union model"
  ([] (get-model nil))
  ([graph-name]
   ;;(if graph-name (.getNamedModel db graph-name) (.getUnionModel db))
   (.getDefaultModel db)
   ))

(defn read-rdf
  ([src] (read-rdf src {}))
  ([src opts] (-> (ModelFactory/createDefaultModel)
                 (.read src nil (jena-rdf-format (:format opts :rdf-xml))))))

(defn store-rdf 
  "Expects src to be compatible with Model.read(src, nil). A java.io.InputStream is
  likely the most appropriate type."
  ([src] (store-rdf src {}))
  ([src opts]
   (write-tx
    (let [m (.getDefaultModel db)
          in (-> (ModelFactory/createDefaultModel)
                 (.read src nil (jena-rdf-format (:format opts :rdf-xml))))]
      (.add m in))
    true)))

(defn- construct-statement 
  ([stmt] (construct-statement stmt {}))
  ([stmt opts]
   (let [[s p o] stmt
         subject (ResourceFactory/createResource s)
         predicate (ResourceFactory/createProperty p)
         object (if (= :Resource (:object (meta stmt)))
                  (ResourceFactory/createResource o)
                  (ResourceFactory/createTypedLiteral o))]
     (ResourceFactory/createStatement subject predicate object))))

(defn load-statements 
  "Statements are a three-item sequence, "
  ([stmts]
   (load-statements stmts nil))
  ([stmts model]
   (tx (let [m (get-model graph-name)
             constructed-statements (into-array Statement (map construct-statement stmts))]
         (.add m constructed-statements))
       true)))

(defn set-ns-prefixes [prefixes]
  (write-tx
   (let [m (.getDefaultModel db)]
     (.clearNsPrefixMap m)
     (.setNsPrefixes m prefixes)
     true)))
