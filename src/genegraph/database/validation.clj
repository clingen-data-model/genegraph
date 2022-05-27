(ns genegraph.database.validation
  (:require [genegraph.database.query :as q]
            [genegraph.database.util :refer [tx]]
            [io.pedestal.log :as log])
  (:import [org.apache.jena.rdf.model Model Resource ModelFactory]
           [org.apache.jena.shacl ShaclValidator Shapes ValidationReport]))

(defn validate 
  "Validate the model passed in, given the contraints model. Return the model containing validation issues."
  [model constraints]
  (try
    (let [shapes (Shapes/parse constraints)
          results (-> (ShaclValidator/get) (.validate shapes model))]
      (.getModel results))
    (catch Exception e# (log/error :fn :tx :msg e#))))

(defn did-validate?
  [report]
  (= 1 (count (tx (q/select "select ?x where { ?x :shacl/conforms true }" {} report)))))


