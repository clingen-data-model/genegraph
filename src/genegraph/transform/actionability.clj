(ns genegraph.transform.actionability
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.core :refer [transform-doc src-path]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]))


(spec/def :condition/iri #(or (re-matches #"http://purl\.obolibrary\.org/obo/OMIM_\d+" %)
                              (re-matches #"http://purl\.obolibrary\.org/obo/MONDO_\d+" %)))

(spec/def ::iri #(re-find #"^https://actionability\.clinicalgenome\.org/ac" %))

(spec/def ::gene #(re-matches #"HGNC:\d+" %))

(spec/def ::statusFlag #(#{"Released" "Released - Under Revision"} %))

(spec/def ::condition
  (spec/keys :req-un [:condition/iri ::gene]))

(spec/def ::conditions
  (spec/coll-of ::condition))

(spec/def ::name string?)

(spec/def ::affiliation
  (spec/keys :req-un [::name]))

(spec/def ::affiliations
  (spec/coll-of ::affiliation))

(spec/def ::curation
  (spec/keys :req-un [::statusFlag ::conditions ::affiliations]))

(defn genetic-condition-label [parent-condition gene]
  (str (q/ld1-> parent-condition [:rdfs/label]) ", " (q/ld1-> gene [:skos/preferred-label])))

(defn genetic-condition [curation-iri condition]
  (when-let [condition-resource (if (re-find #"MONDO" (:iri condition))
                                  (q/resource (:iri condition))
                                  (first (filter #(re-find #"MONDO" (str %))
                                                 (q/ld-> (q/resource (:iri condition))
                                                         [[:owl/equivalent-class :-]]))))]
    (let [gc-node (l/blank-node)
          gene (q/ld1-> (q/resource (:gene condition)) [[:owl/same-as :<]])]
      [[curation-iri :sepio/is-about-condition gc-node]
       [gc-node :rdf/type :sepio/GeneticCondition]
       [gc-node :rdf/type :cg/ActionabilityGeneticCondition]
       [gc-node :rdfs/sub-class-of condition-resource]
       [gc-node :sepio/is-about-gene gene]
       [gc-node :rdfs/label (genetic-condition-label condition-resource gene)]])))

(defn search-contributions [curation-iri search-date agent-iri]
  (let [contrib-iri (l/blank-node)]
    [[curation-iri :sepio/qualified-contribution contrib-iri]
     [contrib-iri :sepio/activity-date search-date]
     [contrib-iri :bfo/realizes :sepio/EvidenceRole]
     [contrib-iri :sepio/has-agent agent-iri]]))

(defn transform [curation]
  (let [statements (if (spec/valid? ::curation curation)
                     (let [curation-iri (:iri curation)
                           contrib-iri (l/blank-node)
                           agent-iri (l/blank-node)]
                       (concat 
                        [[curation-iri :rdf/type :sepio/ActionabilityReport]
                         [curation-iri :sepio/qualified-contribution contrib-iri]
                         [curation-iri :dc/source (:scoreDetails curation)]
                         [contrib-iri :sepio/activity-date (:dateISO8601 curation)]
                         [contrib-iri :bfo/realizes :sepio/ApproverRole]
                         [contrib-iri :sepio/has-agent agent-iri]
                         [agent-iri :rdfs/label (-> curation :affiliations first :name)]]
                        (mapcat #(genetic-condition curation-iri %) (:conditions curation))
                        (mapcat #(search-contributions curation-iri % agent-iri)
                                (:searchDates curation))))
                     [])] 
    (l/statements-to-model statements)))

(defmethod transform-doc :actionability-v1 [doc-def]
  (let [doc (or (:document doc-def) (slurp (src-path doc-def)))]
    (transform (json/parse-string doc true))))


