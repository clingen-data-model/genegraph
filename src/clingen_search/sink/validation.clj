(ns clingen-search.sink.validation
  (:require [clingen-search.database.load :as l]
            [clingen-search.database.query :as q]
            [clingen-search.database.util :refer [tx]]
            [clojure.java.io :as io])
  (:import [org.apache.jena.rdf.model Model Resource ModelFactory]
           org.topbraid.jenax.util.JenaUtil
           org.topbraid.shacl.util.ModelPrinter
           org.topbraid.shacl.validation.ValidationUtil))

(defn validate 
  "Validate the model passed in, given the contraints model. Return the model containing validation issues."
  [model constraints]
  (let [results (ValidationUtil/validateModel model constraints true)]
    (.getModel results)))




