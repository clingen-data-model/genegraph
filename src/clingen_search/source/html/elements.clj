(ns clingen-search.source.html.elements
  (:require [clingen-search.database.query :as q]))

(defn resource-dispatch [resource]
  (if-let [t (:rdf/type resource)]
    (q/to-ref t)
    ::default))

(defmulti page resource-dispatch)


