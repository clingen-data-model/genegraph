(ns genegraph.annotate.interface)

(defmulti add-dataset :genegraph.transform.core/format)
(defmulti add-dataset-commands :genegraph.transform.core/format)
