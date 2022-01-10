(ns genegraph.transform.types
  (:require [genegraph.env :as env]))

(defmulti add-model :genegraph.transform.core/format)

(defmulti model-to-jsonld
          "Turn model into JSON-LD string. Takes an event and returns a string.
          Uses transform format to dispatch."
          :genegraph.transform.core/format)

(defmulti transform-doc :format)

(defn target-base []
  (str env/data-vol "/base/"))

(defn src-path [doc-def] (str (target-base) (:target doc-def)))


