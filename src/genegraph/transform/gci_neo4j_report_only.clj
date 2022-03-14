(ns genegraph.transform.gci-neo4j-report-only
  (:require [genegraph.transform.types :refer [add-model]]
            [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.string :as s]))

(def gci-root "http://dataexchange.clinicalgenome.org/gci/")
(def affiliation-root "http://dataexchange.clinicalgenome.org/agent/")

(defn json-content-node [report iri]
  [[iri :rdf/type :cnt/ContentAsText]
   [iri :cnt/chars (:score-string report)]])

(defn gci-neo4j-export-to-triples [report]
  (let [root-version (str gci-root (:id report))
        id (:id report)
        iri (q/resource (str gci-root id "_report"))
        content-id (l/blank-node)
        assertion-id (q/resource (str gci-root id))]
    (concat [[iri :rdf/type :sepio/GeneValidityReport] 
             [iri :bfo/has-part content-id]
             [iri :bfo/has-part assertion-id]]
            (json-content-node report content-id)))) 

(defmethod add-model :gene-validity-neo4j-export-report-only [event]
  (let [report (:genegraph.sink.event/value event)
        triples (gci-neo4j-export-to-triples report)]
    (assoc event
           ::q/model
           (l/statements-to-model triples))))
