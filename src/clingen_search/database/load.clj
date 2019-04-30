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
  "Statements are a three-item sequence. Will be imported as a named graph into TDB"
  ([stmts graph-name]
   (write-tx (let [m (ModelFactory/createDefaultModel)
             constructed-statements (into-array Statement (map construct-statement stmts))]
         (.add m constructed-statements)
         (.replaceNamedModel db graph-name m))
       true)))

(defn set-ns-prefixes [prefixes]
  (write-tx
   (let [m (.getUnionModel db)]
     (.clearNsPrefixMap m)
     (.setNsPrefixes m prefixes)
     true)))