(ns genegraph.annotate
  (:require [genegraph.database.query :as q]
            [genegraph.transform.core :as transform]
            [genegraph.transform.gci-legacy :as gci-legacy]
            [genegraph.transform.actionability :as aci]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def formats (-> "formats.edn" io/resource slurp edn/read-string))

(defn add-metadata [event]
  (merge event (get formats (::format event))))

(defn add-iri [event]
  (let [iri (-> (q/select "select ?x where {?x a ?type}"
                          {:type (::root-type event)}
                          (::q/model event))
                first
                str)]
    (assoc event ::iri iri)))

(defn add-gene
  "Annotate event with topic genes."
  [event])

(defn add-model 
  "Annotate event with model derived from its value"
  [event]
  (transform/add-model event))
