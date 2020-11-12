(ns genegraph.transform.gci
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.types :refer [transform-doc src-path]]
            [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.walk :as walk]))



(defn record-type [record]
  (-> record (get (keyword "@type")) first))

(defn iri [record]
  (-> record (get (keyword "@id"))))

(defn gene-validity-assertion [gdi assertion-id]
  [[assertion-id :rdf/type :sepio/GeneValidityEvidenceLevelAssertion]])

(defn keys-in [m]
  (if (map? m)
    (vec 
     (mapcat (fn [[k v]]
               (let [sub (keys-in v)
                     nested (map #(into [k] %) (filter (comp not empty?) sub))]
                 (if (seq nested)
                   nested
                   [[k]])))
             m))
    []))


(defn gdi-to-triples [gdi]
  (let [report-id (iri gdi)
        assertion-id (str "/assertion" (iri gdi))]
    (concat [[report-id :rdf/type :sepio/GeneValidityReport]
             [report-id :bfo/has-part assertion-id]]
            (gene-validity-assertion gdi assertion-id))))
