(ns genegraph.transform.core
  (:require [genegraph.database.load :as l]
            [clojure.java.io :as io]
            [genegraph.env :as env]))

(defn target-base []
  (str env/data-vol "/base/"))

(defn src-path [doc-def] (str (target-base) (:target doc-def)))

(defmulti add-model ::format)

(defmulti transform-doc :format)

(defmethod transform-doc :rdf [doc-def] 
  (with-open [is (if (:target doc-def)
                   (io/input-stream (str (target-base) (:target doc-def)))
                   (-> doc-def :document .getBytes java.io.ByteArrayInputStream.))] 
    (l/read-rdf is (:reader-opts doc-def))))

(defn add-model-with-format [event format]
  (with-open [is (cond 
                   (:genegraph.sink.event/value event)
                   (-> event :genegraph.sink.event/value .getBytes java.io.ByteArrayInputStream.)
                   (:genegraph.sink.base/document event)
                   (io/input-stream (str (target-base) (:genegraph.sink.base/document event))))] 
    (assoc event :genegraph.database.query/model (l/read-rdf is {:format format}))))

(defmethod add-model :rdf-xml [event] 
  (add-model-with-format event :rdf-xml))

(defmethod add-model :json-ld [event] 
  (add-model-with-format event :json-ld))

(defmethod add-model :turtle [event] 
  (add-model-with-format event :turtle))


