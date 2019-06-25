(ns clingen-search.transform.core
  (:require [clingen-search.database.load :as l]
            [clojure.java.io :as io]))

(def target-base "data/base/")

(defn src-path [doc-def] (str target-base (:target doc-def)))

(defmulti transform-doc :format)

(defmethod transform-doc :rdf 
  ([doc-def] 
   (with-open [is (io/input-stream (str target-base (:target doc-def)))] 
     (l/read-rdf is (:reader-opts doc-def)))))
