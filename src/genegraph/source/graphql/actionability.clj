(ns genegraph.source.graphql.actionability
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(defresolver actionability-query [args value]
  (q/resource (:iri args)))

(def report-date-query 
  (q/create-query 
   (str "select ?contribution where "
        " { ?report :sepio/qualified-contribution ?contribution . "
        "   ?contribution :bfo/realizes :sepio/EvidenceRole . "
        "   ?contribution :sepio/activity-date ?date } "
        " order by desc(?date) "
        " limit 1 ")))

(defresolver report-date [args value]
  (some-> (report-date-query {:report value}) first :sepio/activity-date first))

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
