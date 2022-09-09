(ns genegraph.database.names
  (:require [mount.core :as mount :refer [defstate]]
            [genegraph.database.instance :refer [db]]
            [genegraph.database.util :refer [property tx select]]
            [camel-snake-kebab.core :as csk]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as s])
  (:import [org.apache.jena.shared PrefixMapping]
           [org.apache.jena.rdf.model Model ModelFactory Literal Resource ResourceFactory]))

(def prefix-ns-map
  (-> "namespaces.edn" io/resource slurp edn/read-string))

(def ns-prefix-map
  (set/map-invert prefix-ns-map))

(defn get-label [resource]
  (let [p (property "http://www.w3.org/2000/01/rdf-schema#label")
        m (.getUnionModel db)]
    (when-let [lbl (-> (.listStatements m resource p nil) iterator-seq first)]
      (-> lbl .getString (s/replace #"\W" " ")))))

(defn resources-with-type [t]
  (let [m (.getUnionModel db)
        statements (iterator-seq
                    (.listStatements
                     m nil
                     (property "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
                     (.createResource m t)))]
    (map #(.getSubject %) statements)))

(defn curie [iri]
  (if-let [[prefix curie-result] (some #(when (s/starts-with? iri (first %)) %) ns-prefix-map)]
    (str (s/upper-case curie-result) ":" (subs iri (count prefix)))
    iri))

(defn- label-valid? [l]
  (and l
       (re-find #"^\D" l)))

(defn- local-name-uri [resource]
  (let [ns (curie (.getURI resource))
        label (some-> resource get-label csk/->kebab-case)]
    (if (label-valid? label)
      [(if ns (keyword ns label) (keyword label))
       (.getURI resource)]
      nil)))

(defn object-properties []
  (tx
   (->> (resources-with-type "http://www.w3.org/2002/07/owl#ObjectProperty")
        (concat (resources-with-type "http://www.w3.org/2002/07/owl#DatatypeProperty"))
        (concat (resources-with-type "http://www.w3.org/2002/07/owl#AnnotationProperty"))
        (concat (resources-with-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"))
        (map local-name-uri)
        (remove nil?)
        (into []))))

;;(-> "property-names.edn" io/resource slurp edn/read-string)
(defn- read-local-property-names []
  (let [kw-to-iri  (-> "property-names.edn" io/resource slurp edn/read-string)]
    (into {} (map (fn [[k v]]  [k (ResourceFactory/createProperty v)]) kw-to-iri))))



(defn- local-name-uri-class [resource]
  (let [label (some-> resource get-label csk/->PascalCase)
        ns (curie (.getURI resource))]
    (if (label-valid? label)
      [(if ns (keyword ns label) (keyword label))
       (.getURI resource)]
      nil)))

(defn class-names []
  (tx (->> (select "select distinct ?x where { [] a ?x }")
           (map local-name-uri-class)
           (remove nil?)
           (into []))))

;;(-> "class-names.edn" io/resource slurp edn/read-string)
(defn- read-local-class-names []
  (let [kw-to-iri  (-> "class-names.edn" io/resource slurp edn/read-string)]
    (into {} (map (fn [[k v]]  [k (ResourceFactory/createResource v)]) kw-to-iri))))

(def local-property-names
  (read-local-property-names))

(def local-class-names
  (read-local-class-names))

(def property-uri->keyword
  (set/map-invert local-property-names))

(def class-uri->keyword
  (set/map-invert local-class-names))

(def local-names
  (merge local-class-names local-property-names))
