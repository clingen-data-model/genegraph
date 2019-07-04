(ns clingen-search.source.graphql.actionability
  (:require [clingen-search.database.query :as q]))

(defn actionability-query [context args value]
  (q/resource (:iri args)))

(defn report-date [context args value]
  (->
   (q/ld-> value 
           [:sepio/qualified-contribution :sepio/activity-date])
   sort
   last))

(defn wg-label [context args value]
  "Actionability WG")

(defn classification-description [context args value]
  "View report for scoring details")
