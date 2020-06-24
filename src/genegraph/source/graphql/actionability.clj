(ns genegraph.source.graphql.actionability
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(defresolver actionability-query [args value]
  (q/resource (:iri args)))

(defresolver report-date [args value]
  (->
   (q/ld-> value 
           [:sepio/qualified-contribution :sepio/activity-date])
   sort
   last))

(defresolver report-id [args value]
  (->> value str (re-find #"\w+$")))

(defresolver wg-label [args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/has-agent :rdfs/label]))

(defresolver classification-description [args value]
  "View report for scoring details")

(defresolver conditions [args value]
  (:sepio/is-about-condition value))

(defresolver source [args value]
  (q/ld1-> value [:dc/source]))
