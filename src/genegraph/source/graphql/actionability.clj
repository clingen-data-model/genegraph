(ns genegraph.source.graphql.actionability
  (:require [genegraph.database.query :as q]))

(defn actionability-query [context args value]
  (q/resource (:iri args)))

(defn report-date [context args value]
  (->
   (q/ld-> value 
           [:sepio/qualified-contribution :sepio/activity-date])
   sort
   last))

(defn report-id [context args value]
  (->> value str (re-find #"\w+$")))

(defn wg-label [context args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/has-agent :rdfs/label]))

(defn classification-description [context args value]
  "View report for scoring details")

(defn conditions [context args value]
  (:sepio/is-about-condition value))

(defn source [context args value]
  (q/ld1-> value [:dc/source]))
