(ns clingen-search.database.names
  (:require [mount.core :as mount :refer [defstate]]
            [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [property tx select]]
            [camel-snake-kebab.core :as csk]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as s])
  (:import [org.apache.jena.shared PrefixMapping]
           [org.apache.jena.rdf.model Model ModelFactory Literal Resource ResourceFactory]))

(defn get-ns-prefix-map []
  (-> "namespaces.edn" io/resource slurp edn/read-string set/map-invert))

(defstate ns-prefix-map
  :start (get-ns-prefix-map))

(defn get-label [resource]
  (let [p (property "http://www.w3.org/2000/01/rdf-schema#label")
        m (.getDefaultModel db)]
    (when-let [lbl (-> (.listStatements m resource p nil) iterator-seq first)]
      (.getString lbl))))

(defn resources-with-type [t]
  (let [m (.getDefaultModel db)
        statements (iterator-seq
                    (.listStatements
                     m nil 
                     (property "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
                     (.createResource m t)))]
           (map #(.getSubject %) statements)))

(defn curie [iri]
  (some #(when (s/starts-with? iri (first %)) (second %)) ns-prefix-map))

(defn -local-name-uri [resource]
  (let [ns (curie (.getURI resource))
        label (some-> resource get-label csk/->kebab-case)]
    (if label
      [(if ns (keyword ns label) (keyword label))
       (property (.getURI resource))]
      [nil nil])))

(defn object-properties []
  (tx 
   (->> (resources-with-type "http://www.w3.org/2002/07/owl#ObjectProperty")
        (concat (resources-with-type "http://www.w3.org/2002/07/owl#DatatypeProperty"))
        (concat (resources-with-type "http://www.w3.org/2002/07/owl#AnnotationProperty"))
        (concat (resources-with-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"))
        (map -local-name-uri)
        (into {}))))

(defstate local-property-names
  :start (object-properties))

(defn -local-name-uri-class [resource]
  (let [label (some-> resource get-label csk/->PascalCase)
        ns (curie (.getURI resource))]
    (if label
      [(if ns (keyword ns label) (keyword label))
       resource]
      [nil nil])))

(defn class-names []
  (tx (->> (select "select distinct ?x where { [] a ?x }")
           (map -local-name-uri-class)
           (into {}))))

(defstate local-class-names
  :start (class-names))
