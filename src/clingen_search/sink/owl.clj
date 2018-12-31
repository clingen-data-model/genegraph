(ns clingen-search.sink.owl
  (:require [clojure.java.io :as io]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [clingen-search.database.neo4j :as db]
            [clojure.pprint :refer [pprint]])
  (:import [org.apache.jena.rdf.model Model ModelFactory Literal Resource]
           [org.apache.jena.ontology OntResource]))

(def to-case csk/->snake_case)

(defn ontology-model [source_files]
  (let [model (ModelFactory/createOntologyModel)]
    (doseq [f source_files]
      (with-open [is (io/input-stream f)]
        (.read model is nil)))
    model))

(defn label [resource]
  (when-let [l (.getLabel resource nil)]
    (to-case l)))

(defn unlabeled? [resource]
  (not (and (.getURI resource) (label resource))))

;; (defn make-object-property-tuple [resource]
;;   [(label resource) {"@id" (curied-uri resource context-curies), "@type" "@id"}])

(defn object-properties [model]
  (-> model .listObjectProperties iterator-seq))

(defn context-object-properties [model]
  (->> model object-properties (remove unlabeled?) (map make-object-property-tuple)
       (sort-by first)))

;; (defn make-data-property-tuple [resource]
;;   [(label resource) (curied-uri resource context-curies)])

(defn data-properties [model]
  (-> model .listDatatypeProperties iterator-seq))

(defn context-data-properties [model]
  (->> model data-properties (remove unlabeled?) (map make-data-property-tuple)
       (sort-by first)))

(defn context-properties [model]
  (concat (context-object-properties model) (context-data-properties model)))

(defn properties-with-duplicate-labels [property-list]
  (let [label-frequencies (frequencies (map first property-list))
        duplicate-labels (into #{} (filter #(> (val %) 1) label-frequencies))]
    (filter #(duplicate-labels (first %)) property-list)))

(defn report-duplicates [property-list]
  (when-let [dups (seq (properties-with-duplicate-labels property-list))]
    (println "Duplicate property labels exist:")
    (clojure.pprint/pprint dups)))

