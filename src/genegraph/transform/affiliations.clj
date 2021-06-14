(ns genegraph.transform.affiliations
  (:require [clojure.data.csv :as csv]
            [clojure.string :as s]
            [genegraph.database.load :as l]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]))

(def affiliation-prefix "http://dataexchange.clinicalgenome.org/agent/")

(defn affiliation [[id label]]
  (if (and (> (count id) 0)
           (> (count label) 0))
    (let [iri (str affiliation-prefix id)]
      [[iri :skos/preferred-label (s/trim label)]
       [iri :rdf/type :cg/Affiliation]])
    []))

(defn affiliation-to-triples [affiliation-row]
  (let [[label id _ _ _ _ _ vcep-id vcep-label gcep-id gcep-label] affiliation-row
        affiliation-list [[id label] [vcep-id vcep-label] [gcep-id gcep-label]]]
    (mapcat affiliation affiliation-list)))

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
