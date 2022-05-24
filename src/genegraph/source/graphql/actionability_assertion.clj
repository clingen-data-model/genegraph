(ns genegraph.source.graphql.actionability-assertion
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(def report-date-query 
  (q/create-query 
   (str "select ?contribution where "
        " { ?report :bfo/has-part ?assertion . "
        "   ?report :sepio/qualified-contribution ?contribution . "
        "   ?contribution :bfo/realizes :sepio/EvidenceRole . "
        "   ?contribution :sepio/activity-date ?date } "
        " order by desc(?date) "
        " limit 1 ")))

(defresolver report-date [args value]
  (some-> (report-date-query {:assertion value}) first :sepio/activity-date first))

(defresolver source [args value]
  (q/ld1-> value [[:bfo/has-part :<] :dc/source]))

(defresolver classification [args value]
  (q/ld1-> value [:sepio/has-predicate]))

(defresolver report-label [args value]
  (q/ld1-> value [[:bfo/has-part :<] :rdfs/label]))

(defresolver attributed-to [args value]
  (q/ld1-> value [[:bfo/has-part :<] :sepio/qualified-contribution :sepio/has-agent]))
