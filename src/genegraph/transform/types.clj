(ns genegraph.transform.types
  (:require [genegraph.env :as env]))

(defmulti add-model :genegraph.transform.core/format)

(defmulti add-model-jsonld
          "Turn model into JSON-LD string. Takes an event and returns it with :genegraph.annotate/jsonld.
          Uses transform format to dispatch."
          :genegraph.transform.core/format)

(defmulti transform-doc :format)

(defn target-base []
  (str env/data-vol "/base/"))

(defn src-path [doc-def] (str (target-base) (:target doc-def)))


