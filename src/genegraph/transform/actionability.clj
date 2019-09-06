(ns genegraph.transform.actionability
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.core :refer [transform-doc src-path]]
            [cheshire.core :as json]
            [clojure.java.io :as io]))


(defn genetic-condition-label [parent-condition gene]
  (str (q/ld1-> parent-condition [:rdfs/label]) ", " (q/ld1-> gene [:skos/preferred-label])))

;; TODO Validate form of input (curation MUST have a condition, conditions MUST have a gene)
(defn genetic-condition [curation-iri condition]
  (when-let [condition-resource (q/ld1-> (q/resource (:iri condition)) [[:owl/equivalent-class :<]])]
    (if (or (q/is-rdf-type? condition-resource :sepio/GeneticCondition)
            (not (:gene condition)))
      [[curation-iri :sepio/is-about-condition condition-resource]]
      (let [gc-node (l/blank-node)
            gene (q/ld1-> (q/resource (:gene condition)) [[:owl/same-as :<]])]
        [[curation-iri :sepio/is-about-condition gc-node]
         [gc-node :rdf/type :sepio/GeneticCondition]
         [gc-node :rdfs/sub-class-of condition-resource]
         [gc-node :sepio/is-about-gene gene]
         [gc-node :rdfs/label (genetic-condition-label condition-resource gene)]]))))

(defn search-contributions [curation-iri search-date agent-iri]
  (let [contrib-iri (l/blank-node)]
    [[curation-iri :sepio/qualified-contribution contrib-iri]
     [contrib-iri :sepio/activity-date search-date]
     [contrib-iri :bfo/realizes :sepio/EvidenceRole]
     [contrib-iri :sepio/has-agent agent-iri]]))

(defn transform [curation]
  (let [curation-iri (:iri curation)
        contrib-iri (l/blank-node)
        agent-iri (l/blank-node)
        statements (concat 
                    [[curation-iri :rdf/type :sepio/ActionabilityReport]
                     [curation-iri :sepio/qualified-contribution contrib-iri]
                     [curation-iri :dc/source (:scoreDetails curation)]
                     [contrib-iri :sepio/activity-date (:dateISO8601 curation)]
                     [contrib-iri :bfo/realizes :sepio/ApproverRole]
                     [contrib-iri :sepio/has-agent agent-iri]
                     [agent-iri :rdfs/label (-> curation :affiliations first :name)]]
                    (mapcat #(genetic-condition curation-iri %) (:conditions curation))
                    (mapcat #(search-contributions curation-iri % agent-iri)
                            (:searchDates curation)))]
    (l/statements-to-model statements)))

(defmethod transform-doc :actionability-v1
  ([doc-def] (transform-doc doc-def (slurp (src-path doc-def))))
  ([doc-def doc] (transform (json/parse-string doc true))))


