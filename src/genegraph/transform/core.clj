(ns genegraph.transform.core
  (:require [genegraph.database.load :as l]
            [clojure.java.io :as io]
            [genegraph.env :as env]))

(def target-base (str env/data-vol "/base/"))

(defn src-path [doc-def] (str target-base (:target doc-def)))

(defmulti transform-doc :format)

(defmethod transform-doc :rdf [doc-def] 
  (with-open [is (if (:target doc-def)
                   (io/input-stream (str target-base (:target doc-def)))
                   (-> doc-def :document .getBytes java.io.ByteArrayInputStream.))] 
    (l/read-rdf is (:reader-opts doc-def))))
