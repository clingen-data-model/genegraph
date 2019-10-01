(ns genegraph.source.graphql.property
  (:require [genegraph.database.query :as q]))

(defn iri [context args value]
  (str (q/ld1-> value [:shacl/path])))

(defn label [context args value]
  (q/ld1-> value [:shacl/path :rdfs/label]))

(defn definition [context args value]
  (q/ld1-> value [:shacl/path :iao/definition]))

(defn min-count [context args value]
  (or (q/ld1-> value [:shacl/min-count])
      (q/ld1-> value [:shacl/qualified-min-count])))

(defn max-count [context args value]
  (or (q/ld1-> value [:shacl/max-count])
      (q/ld1-> value [:shacl/qualified-max-count])))

(defn display-arity [context args value]
  (str (or (min-count context args value) 0)
       ".."
       (or (max-count context args value) "*")))
