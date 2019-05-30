(ns clingen-search.sink.validation
  (:require [clingen-search.database.load :as l]
            [clingen-search.database.query :as q]
            [clingen-search.database.util :refer [tx]]
            [clojure.java.io :as io])
  (:import [org.apache.jena.rdf.model Model Resource ModelFactory]
           org.topbraid.jenax.util.JenaUtil
           org.topbraid.shacl.util.ModelPrinter
           org.topbraid.shacl.validation.ValidationUtil))

(q/register-query ::validation-report "select ?x where { ?x a :shacl/ValidationReport } limit 1")

(defn validate 
  "Validate the model passed in, given the contraints model. Return the model containing validation issues."
  [model constraints]
  (let [results (ValidationUtil/validateModel model constraints true)]
    (.getModel results)))


(defn did-validate? 
  "Return true if the model validated, false otherwise."
  [validation-report]
  (let [rep-node (first (q/select  ::validation-report {:-model validation-report}))]
    (q/ld1-> rep-node [:shacl/conforms])))

