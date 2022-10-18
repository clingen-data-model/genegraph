(ns genegraph.transform.gci-express
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q :refer [resource]]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]
            [cheshire.core :as json]))

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
        parsed-json (json/parse-string (json-content report) true)
        moi-string (or (-> parsed-json :data :ModeOfInheritance)
                       (-> parsed-json :scoreJson :ModeOfInheritance))
        moi (->> moi-string
                 (re-find #"\(HP:(\d+)\)")
                 second
                 (str "http://purl.obolibrary.org/obo/HP_")
                 q/resource)]
    [[iri :rdf/type :sepio/GeneValidityProposition]
     [iri :sepio/has-subject gene]
     [iri :sepio/has-predicate :ro/IsCausalGermlineMutationIn]
     [iri :sepio/has-object (q/resource (-> report :conditions :MONDO :iri))]
     [iri :sepio/has-qualifier moi]]))

(defn contribution [report iri]
  [[iri :bfo/realizes :sepio/ApproverRole]
   [iri :sepio/has-agent (resource (str affiliation-root
                                        (-> report :affiliation :id)))]
   [iri :sepio/activity-date (:dateISO8601 report)]])

(def evidence-level-label-to-concept
  {"Definitive" :sepio/DefinitiveEvidence
   "Limited" :sepio/LimitedEvidence
   "Moderate" :sepio/ModerateEvidence
   "No Reported Evidence" :sepio/NoEvidence
   "Strong*" :sepio/StrongEvidence
   "Contradictory (disputed)" :sepio/DisputingEvidence
   "Strong" :sepio/StrongEvidence
   "Contradictory (refuted)" :sepio/RefutingEvidence
   "Refuted" :sepio/RefutingEvidence
   "Disputed" :sepio/DisputingEvidence})

(defn sop-version-gci-e [report]
  (if (< 0 (count (:scoreJsonSerialized report)))
    :sepio/ClinGenGeneValidityEvaluationCriteriaSOP4
    :sepio/ClinGenGeneValidityEvaluationCriteriaSOP5))

(defn evidence-level-assertion [report iri id]
  (let [prop-iri (resource (str gci-express-root "proposition_" id))
        contribution-iri (l/blank-node)]
    (concat [[iri :rdf/type :sepio/GeneValidityEvidenceLevelAssertion]
             [iri :cg/website-legacy-id iri] ; for backward compatibility
             [iri :sepio/has-subject prop-iri]
             [iri :sepio/has-predicate :sepio/HasEvidenceLevel]
             [iri :sepio/has-object (evidence-level-label-to-concept
                                     (-> report :scores vals first :label))]
             [iri :sepio/qualified-contribution contribution-iri]
             [iri :sepio/is-specified-by (sop-version-gci-e report)]
             [iri :dc/has-format (sop-version-gci-e report)]]
            (validity-proposition report prop-iri)
            (contribution report contribution-iri))))

(defn gci-express-report-to-triples [report]
  (let [content (second report)
        id (-> report first name)
        iri (str gci-express-root "report_" id)
        content-id (l/blank-node)
        assertion-id (resource (str gci-express-root "assertion_" id))]
    (concat [[iri :rdf/type :sepio/GeneValidityReport] 
             [iri :rdfs/label (:title content)]
             [iri :bfo/has-part content-id]
             [iri :bfo/has-part assertion-id]
             [iri :dc/source :cg/GeneCurationExpress]]
            (evidence-level-assertion content assertion-id id)
            (json-content-node content content-id))))

(defmethod transform-doc :gci-express [doc-def]
  (let [raw-report (or (:document doc-def) (slurp (src-path doc-def)))
        report-json (json/parse-string raw-report true)]
    (l/statements-to-model (mapcat gci-express-report-to-triples report-json))))


(defmethod add-model :gci-express [event]
  (assoc event
         :genegraph.database.query/model
         (l/statements-to-model (gci-express-report-to-triples
                                 (:genegraph.sink.event/value event)))))
