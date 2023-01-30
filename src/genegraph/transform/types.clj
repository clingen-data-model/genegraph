(ns genegraph.transform.types
  (:require [genegraph.env :as env]
            [io.pedestal.log :as log]))

(defmulti add-model :genegraph.transform.core/format)

(defmulti add-model-jsonld
  "Turn model into JSON-LD string. Takes an event and returns it with :genegraph.annotate/jsonld.
   Uses transform format to dispatch."
  :genegraph.transform.core/format)

(defmethod add-model-jsonld :default [event]
  (log/debug :fn :add-model-jsonld
             :msg "No method in multimethod for dispatch"
             :dispatch (:genegraph.transform.core/format event))
  event)

(defmulti add-event-graphql
  "Adds a :genegraph.annotate/graphql field containing:
   :query - string of the graphql query
   :variables - a map of query variables (keys should be keywords, or as expected by resolvers involved)"
  :genegraph.transform.core/format)

(defmethod add-event-graphql :default [event]
  (log/debug :fn :add-event-graphql
             :msg "No method in multimethod for dispatch"
             :dispatch (:genegraph.transform.core/format event))
  event)

(defmulti transform-doc :format)

(defmulti add-data
  "Add an edn-based representation of the data in the event in genegraph.annotate/data.
   For some data formats, may do nothing. Downstream interceptors that read this
   must handle empty values."
  :genegraph.transform.core/format)

(defn target-base []
  (str env/data-vol "/base/"))

(defn src-path [doc-def] (str (target-base) (:target doc-def)))
