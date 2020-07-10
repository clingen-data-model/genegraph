(ns genegraph.annotate.gene
  (:require [genegraph.database.query :as q]))

(defmulti add-genes :genegraph.sink.event/root-type)

(def validity-genes-query
  (q/create-query
   "select ?gene where
{ ?proposition a :sepio/GeneValidityProposition .
  ?proposition :sepio/has-subject ?gene }"))

(defmethod add-genes :sepio/GeneValidityReport [event]
  (let [genes (validity-genes-query event)]))
