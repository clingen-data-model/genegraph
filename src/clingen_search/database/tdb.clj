(ns clingen-search.database.tdb
  (:require [mount.core :as mount :refer [defstate]]
            [clojure.pprint :refer [pprint]]
            [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [property tx write-tx]])
  (:import [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.query TxnType Dataset]
           [org.apache.jena.rdf.model Model ModelFactory Literal Resource ResourceFactory]
           [org.apache.jena.ontology OntResource]))

;; (defn with-class [cl]
;;   (let [m (.getDefaultModel db)
;;         object-class (get local-class-names cl)
;;         property-class (property "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
;;         stmt-iter (.listStatements m nil property-class object-class)]
;;     (map #(.getSubject %) (iterator-seq stmt-iter))))

(def jena-rdf-format
  {:rdf-xml "RDF/XML"
   :json-ld "JSON-LD"
   :turtle "Turtle"})

(defn load-rdf 
  "Expects src to be compatible with Model.read(src, nil). A java.io.InputStream is
  likely the most appropriate type."
  ([src] (load-rdf src {}))
  ([src opts]
   (pprint opts)
   (write-tx
    (let [m (.getDefaultModel db)
          in (-> (ModelFactory/createDefaultModel)
                 (.read src nil (jena-rdf-format (:format opts :rdf-xml))))]
      (.setNsPrefixes m in)
      (.add m in))
    true)))

(defn -construct-statement 
  ([stmt] -construct-statement stmt {})
  ([stmt opts]
   (let [[s p o] stmt
         subject (ResourceFactory/createResource s)
         predicate (ResourceFactory/createProperty p)
         object (if (= :resource (:object-type opts))
                  (ResourceFactory/createResource o)
                  (ResourceFactory/createTypedLiteral o))]
     (ResourceFactory/createStatement subject predicate object))))

(defn load-statements 
  "Statements are a three-item sequence, "
  [stmts]
  (tx (let [m (.getDefaultModel db)
            constructed-statements (into-array (map -construct-statement stmts))]
        (println stmts)
        (println constructed-statements)
        (.add m constructed-statements))
      true))

(defn set-ns-prefixes [prefixes]
  (write-tx
   (let [m (.getDefaultModel db)]
     (.clearNsPrefixMap m)
     (.setNsPrefixes m prefixes)
     true)))
