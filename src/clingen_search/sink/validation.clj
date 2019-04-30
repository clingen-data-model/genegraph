(ns clingen-search.sink.validation
  (:require [clingen-search.database.load :as l]
            [clingen-search.database.query :as q])
  (:import [org.apache.jena.rdf.model Model Resource]
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
  (let [rep-node (q/select  ::validation-report {:-model validation-report})]
    (some->> rep-node first :shacl/conforms first)))
