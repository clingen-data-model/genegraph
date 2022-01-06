(ns genegraph.transform.types
  (:require [genegraph.env :as env]))

(defmulti add-model :genegraph.transform.core/format)

(defmulti transform-doc :format)

(def target-base (str env/data-vol "/base/"))

(defn src-path [doc-def] (str target-base (:target doc-def)))

