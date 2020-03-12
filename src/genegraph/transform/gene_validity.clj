;; TODO contribution, remainder of assertion, including evidence level and methodology

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

(def gci-express-root "http://dataexchange.clinicalgenome.org/gci-express/")
(def affiliation-root "http://dataexchange.clinicalgenome.org/agent/")

(defn json-content [report]
  (if (< 0 (count (:scoreJsonSerialized report)))
    (:scoreJsonSerialized report)
    (:scoreJsonSerializedSop5 report)))

(defn json-content-node [report iri]
  [[iri :rdf/type :cnt/ContentAsText]
   [iri :cnt/chars (json-content report)]])

(defn validity-proposition [report iri]
  (let [gene-hgnc-id (-> report :genes first second :curie)
        gene (when gene-hgnc-id
               (q/ld1-> (q/resource gene-hgnc-id) [[:owl/same-as :<]]))
        moi (->> (-> report json-content (json/parse-string true) :data :ModeOfInheritance)
                 (re-find #"\(HP:(\d+)\)")
                 second
                 (str "http://purl.obolibrary.org/obo/HP_")
                 q/resource)]
    [[iri :rdf/type :sepio/GeneValidityProposition]
     [iri :sepio/has-subject gene]
     [iri :sepio/has-predicate :ro/IsCausalGermlineMutationIn]
     [iri :sepio/has-object (q/resource (-> report :conditions :MONDO :iri))]
     [iri :sepio/has-qualifier moi]]))

(defn evidence-level-assertion [report iri]
  (let [prop-iri (l/blank-node)]
    (concat [[iri :rdf/type :sepio/GeneValidityEvidenceLevelAssertion]
             [iri :sepio/has-subject prop-iri]]
            (validity-proposition report prop-iri))))

(defn gci-express-report-to-triples [report]
  (let [content (second report)
        root-version (str gci-express-root (-> report first name))
        iri (str root-version "-" (:dateISO8601 content))
        content-id (l/blank-node)
        assertion-id (l/blank-node)]
    (concat [[iri :rdf/type :sepio/GeneValidityReport] 
             [iri :bfo/has-part content-id]
             [iri :bfo/has-part assertion-id]]
            (evidence-level-assertion content assertion-id)
            ;;(json-content-node content content-id)
            )))

(defmethod transform-doc :gci-express [doc-def]
  (let [raw-report (or (:document doc-def) (slurp (src-path doc-def)))
        report-json (json/parse-string raw-report true)]
    (l/statements-to-model (mapcat gci-express-report-to-triples report-json))))
