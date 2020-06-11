(ns genegraph.source.graphql.resource
  (:require [genegraph.database.query :as q :refer [create-query]]))

(defn iri [context args value]
  (str value))

(defn curie [context args value]
  (q/curie value))

(def label-memo 
  (memoize (fn [resource] (first (concat (:skos/preferred-label resource)
                                         (:rdfs/label resource))))))

(defn label 
  "Presumption is that the first preferred label is the appropriate one to return."
  [context args resource]
  (label-memo resource)
  ;;(first (concat (:skos/preferred-label resource) (:rdfs/label resource)))
  )

(def alternative-label-memo
  (memoize (fn [resource] (first (:skos/alternative-label resource)))))

(defn alternative-label
  "Return the first :skos/alternative-label that exists. Does not map to any other 
  label definition"
  [context args resource]
  (alternative-label-memo resource)
  ;;(first (:skos/alternative-label resource))
  )

(defn description [context args value]
  (first (:dc/description value)))

(def all-superclasses-query (create-query 
                             (str "select ?superclass where "
                                  " { ?class"
                                  " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                                  " ?superclass } ")))

(defn all-superclasses [context args value]
  (all-superclasses-query {:class value}))

(defn direct-superclasses [context args value]
  (:rdfs/sub-class-of value))

(def all-subclasses-query (create-query 
                           (str "select ?subclass where "
                                " { ?subclass"
                                " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                                " ?class } ")))

(defn all-subclasses [context args value]
  (all-subclasses-query {:class value}))

(defn direct-subclasses [context args value]
  (get value [:rdfs/sub-class-of :<]))
