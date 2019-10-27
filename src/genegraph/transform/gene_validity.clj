(ns genegraph.transform.gene-validity
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.core :refer [transform-doc src-path]]
            [cheshire.core :as json]))

(defn gene-validity-triples [report]
  (let [iri (str "gene-validity:" (:iri report))
        genetic-condition-iri (l/blank-node)
        condition (get-in report [:conditions 0 :iri])
        gene-hgnc-id (get-in report [:genes 0 :curie])
        gene (when gene-hgnc-id
               (q/ld1-> (q/resource gene-hgnc-id) [[:owl/same-as :<]]))
        genetic-condition (when (and condition gene)
                            [[genetic-condition-iri :rdf/type :sepio/GeneticCondition]
                             [genetic-condition-iri :rdfs/sub-class-of condition]])]
    (println "gene-validity:" iri)
    [[iri :sepio/is-about-condition genetic-condition-iri]
     [iri :rdf/type :sepio/GeneValidityReport]]))

(defmethod transform-doc :gene-validity-v1 [doc-def]
  (let [raw-report (or (:document doc-def) (slurp (src-path doc-def)))
        report-json (json/parse-string raw-report true)
        report-triples (gene-validity-triples report-json)]
    (l/statements-to-model report-triples)))
