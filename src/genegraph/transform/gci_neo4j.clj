(ns genegraph.transform.gci-neo4j
  (:require [genegraph.transform.core :as xform :refer [add-model]]
            [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.string :as s]))

(def gci-root "http://dataexchange.clinicalgenome.org/gci/")
(def affiliation-root "http://dataexchange.clinicalgenome.org/agent/")

(defn validity-proposition [report iri]
  [[iri :rdf/type :sepio/GeneValidityProposition]
   [iri :sepio/has-subject (q/resource (:gene report))]
   [iri :sepio/has-predicate :ro/IsCausalGermlineMutationIn]
   [iri :sepio/has-object (q/resource (:disease report))]
   [iri :sepio/has-qualifier (q/resource (:moi report))]])

(def evidence-level-label-to-concept
  {"Definitive" :sepio/DefinitiveEvidence
   "Limited" :sepio/LimitedEvidence
   "Moderate" :sepio/ModerateEvidence
   "No Reported Evidence" :sepio/NoEvidence
   "No Known Disease Relationship" :sepio/NoEvidence
   "Strong*" :sepio/StrongEvidence
   "Contradictory (disputed)" :sepio/DisputingEvidence
   "Strong" :sepio/StrongEvidence
   "Contradictory (refuted)" :sepio/RefutingEvidence
   "Refuted" :sepio/RefutingEvidence
   "Disputed" :sepio/DisputingEvidence
   "No Classification" :sepio/NoEvidence})

(def old-score-to-new
  {"http://datamodel.clinicalgenome.org/terms/CG_000084" :sepio/DisputingEvidence
   "http://datamodel.clinicalgenome.org/terms/CG_000064" :sepio/StrongEvidence
   "http://datamodel.clinicalgenome.org/terms/CG_000066" :sepio/LimitedEvidence
   "http://datamodel.clinicalgenome.org/terms/CG_000067" :sepio/NoEvidence
   "http://datamodel.clinicalgenome.org/terms/CG_000063" :sepio/DefinitiveEvidence
   "http://datamodel.clinicalgenome.org/terms/CG_000085" :sepio/RefutingEvidence
   "http://datamodel.clinicalgenome.org/terms/CG_000065" :sepio/ModerateEvidence})

(def gci-sop-version 
  {"5" :sepio/ClinGenGeneValidityEvaluationCriteriaSOP5
   "6" :sepio/ClinGenGeneValidityEvaluationCriteriaSOP6
   "7" :sepio/ClinGenGeneValidityEvaluationCriteriaSOP7})

(defn contribution [report iri]
  [[iri :bfo/realizes :sepio/ApproverRole]
   [iri :sepio/has-agent  (q/resource 
                           (s/replace (:gcep report)
                                      "https://search.clinicalgenome.org/kb/agents/"
                                      affiliation-root))]
   [iri :sepio/activity-date (:date report)]])

(defn evidence-level-assertion [report iri id]
  (let [prop-iri (q/resource (str gci-root "proposition_" id))
        contribution-iri (l/blank-node)]
    (concat [[iri :rdf/type :sepio/GeneValidityEvidenceLevelAssertion]
             [iri :sepio/has-subject prop-iri]
             [iri :sepio/has-predicate :sepio/HasEvidenceLevel]
             [iri :sepio/has-object (old-score-to-new (:score report))]
             [iri :sepio/qualified-contribution contribution-iri]
             [iri :sepio/is-specified-by (gci-sop-version (:sop-version report))]]
            (validity-proposition report prop-iri)
             (contribution report contribution-iri))))

(defn json-content-node [report iri]
  [[iri :rdf/type :cnt/ContentAsText]
   [iri :cnt/chars (:score-string report)]])

(defn gci-neo4j-export-to-triples [report]
  (let [root-version (str gci-root (:id report))
        id (str (:id report) "-" (s/replace (:date report) #":" ""))
        iri (q/resource (str gci-root "report_" id))
        content-id (l/blank-node)
        assertion-id (q/resource (str gci-root "assertion_" id))]
    (concat [[iri :rdf/type :sepio/GeneValidityReport] 
             [iri :rdfs/label (:title report)]
             [iri :bfo/has-part content-id]
             [iri :bfo/has-part assertion-id]]
            (evidence-level-assertion report assertion-id id)
            (json-content-node report content-id)))) 

(defmethod add-model :gene-validity-neo4j-export [event]
  (let [report (:genegraph.sink.event/value event)
        triples (gci-neo4j-export-to-triples report)]
    (assoc event
           ::q/model
           (l/statements-to-model triples))))
