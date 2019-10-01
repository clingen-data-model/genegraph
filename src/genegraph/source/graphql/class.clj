(ns genegraph.source.graphql.class
  (:require [genegraph.database.query :as q]))

(defn model-classes-query [context args value]
  (q/select "select ?x where { [] :shacl/class ?x }"))

(defn definition [context args value]
  (first (:iao/definition value)))

(defn subclasses [context args value]
  (get value [:rdfs/sub-class-of :<]))

(defn superclasses [context args value]
  (:rdfs/sub-class-of value))

(defn properties [context args value]
  (q/ld-> value [[:shacl/class :<] :shacl/property]))
