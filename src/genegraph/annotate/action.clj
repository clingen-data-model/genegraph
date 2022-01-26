(ns genegraph.annotate.action
  (:require [cheshire.core :as json]))

(defmulti add-action :genegraph.transform.core/format)

(defmethod add-action :default [event]
  (assoc event :genegraph.annotate/action :publish))

(defmethod add-action :gci-legacy [event]
  (let [report-json (json/parse-string (:genegraph.sink.event/value event) true)]
    (assoc event 
           :genegraph.annotate/action 
           (case (:statusPublishFlag report-json)
             "Publish" :publish
             "Unpublish" :unpublish))))

(defmethod add-action :actionability-v1 [event]
  (let [report-json (json/parse-string (:genegraph.sink.event/value event) true)]
    (assoc event 
           :genegraph.annotate/action 
           (case (:statusFlag report-json)
             "Retracted" :unpublish
             "In Preparation" :no-action
             :publish))))

(defmethod add-action :gci-refactor [event]
  (let [action (if (re-find #"\"publishClassification\": ?true"
                            (:genegraph.sink.event/value event))
                 :publish
                 :unpublish)]
    (assoc event :genegraph.annotate/action action)))
