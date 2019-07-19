(ns clingen-search.transform.actionability
  (:require [clingen-search.database.load :as l]
            [clingen-search.database.query :as q]
            [clingen-search.transform.core :refer [transform-doc src-path]]
            [cheshire.core :as json]
            [clojure.java.io :as io]))


;; TODO Validate form of input (curation MUST have a condition, conditions MUST have a gene)
(defn genetic-condition [curation-iri condition]
  (let [condition-resource (q/resource (:iri condition))]
    (if (or (q/is-rdf-type? condition-resource :sepio/GeneticCondition)
            (not (:gene condition)))
      [[curation-iri :sepio/is-about-condition condition-resource]]
      (let [gc-node (l/blank-node)
            gene (q/ld1-> (q/resource (:gene condition)) [[:owl/same-as :<]])]
        (println (:gene condition))
        (println (str gene))
        [[curation-iri :sepio/is-about-condition gc-node]
         [gc-node :rdf/type :sepio/GeneticCondition]
         [gc-node :rdfs/sub-class-of condition-resource]
         [gc-node :sepio/is-about-gene gene]]))))

(defn search-contributions [curation-iri search-date]
  (let [contrib-iri (l/blank-node)]
    [[curation-iri :sepio/qualified-contribution contrib-iri]
     [contrib-iri :sepio/activity-date search-date]
     [contrib-iri :bfo/realizes :sepio/EvidenceRole]]))

(defn transform [curation]
  (let [curation-iri (:iri curation)
        contrib-iri (l/blank-node)
        statements (concat 
                    [[curation-iri :rdf/type :sepio/ActionabilityReport]
                     [curation-iri :sepio/qualified-contribution contrib-iri]
                     [contrib-iri :sepio/activity-date (:dateISO8601 curation)]
                     [contrib-iri :bfo/realizes :sepio/ApproverRole]]
                    (mapcat #(genetic-condition curation-iri %) (:conditions curation))
                    (mapcat #(search-contributions curation-iri %) (:searchDates curation)))]
    (l/statements-to-model statements)))

(defmethod transform-doc :actionability-v1
  ([doc-def] (transform-doc doc-def (slurp (src-path doc-def))))
  ([doc-def doc] (transform (json/parse-string doc true))))


