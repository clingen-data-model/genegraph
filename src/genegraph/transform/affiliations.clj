(ns genegraph.transform.affiliations
  (:require [clojure.data.csv :as csv]
            [clojure.string :as s]
            [genegraph.database.load :as l]
            [genegraph.transform.core :refer [transform-doc src-path add-model]]))

(def affiliation-prefix "http://dataexchange.clinicalgenome.org/agent/")

(defn affiliation-to-triples [affiliation-row]
  (let [[label id] affiliation-row
        iri (str affiliation-prefix id)]
    [[iri :skos/preferred-label (s/trim label)]
     [iri :rdf/type :cg/Affiliation]]))

(defn affiliations-to-triples [affiliations-csv]
  (mapcat affiliation-to-triples (rest affiliations-csv)))

(defmethod transform-doc :affiliations [doc-def]
  (->> doc-def 
       src-path
       slurp
       csv/read-csv
       affiliations-to-triples
       l/statements-to-model))

(defmethod add-model :affiliations [event]
  (assoc event
         :genegraph.database.query/model
         (->> event
              :genegraph.sink.event/value
              csv/read-csv
              affiliations-to-triples
              l/statements-to-model)))
