(ns genegraph.sink.event
  (:require [genegraph.transform.core :refer [add-model]]
            [genegraph.database.query :as q]
            [genegraph.database.load :refer [load-model]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def formats (-> "formats.edn" io/resource slurp edn/read-string))

(defn add-metadata [event]
  (merge event (get formats (::format event))))

(defn add-iri [event]
  (let [iri (-> (q/select "select ?x where {?x a ?type}"
                          {:type (::root-type event)}
                          (::model event))
                first
                str)]
    (assoc event ::iri iri)))

(defn add-to-db! [event]
  (load-model (::model event) (::iri event))
  event)

(defn process-event! [event]
  (-> event
      add-metadata
      add-model
      add-iri
      add-to-db!))
