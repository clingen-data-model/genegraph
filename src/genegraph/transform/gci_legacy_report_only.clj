(ns genegraph.transform.gci-legacy-report-only
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q :refer [resource]]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]
            [clojure.string :as s]
            [cheshire.core :as json]
            [io.pedestal.log :as log]
            [clojure.spec.alpha :as spec]))

(spec/def ::curation
  (spec/keys :req-un [::iri]))

(def gci-root "http://dataexchange.clinicalgenome.org/gci/")
(def affiliation-root "http://dataexchange.clinicalgenome.org/agent/")

(defn report-date [report]
  (get-in report [:scoreJson :summary :FinalClassificationDate]))

;; (defn validity-proposition [report iri]
;;   (let [gene-hgnc-id (-> report :genes first :curie)
;;         gene (when gene-hgnc-id
;;                (q/ld1-> (q/resource gene-hgnc-id) [[:owl/same-as :<]]))
;;         moi-string (-> report :scoreJson :ModeOfInheritance)
;;         moi (->> moi-string
;;                  (re-find #"\(HP:(\d+)\)")
;;                  second
;;                  (str "http://purl.obolibrary.org/obo/HP_")
;;                  q/resource)]
;;     [[iri :rdf/type :sepio/GeneValidityProposition]
;;      [iri :sepio/has-subject gene]
;;      [iri :sepio/has-predicate :ro/IsCausalGermlineMutationIn]
;;      [iri :sepio/has-object (q/resource (-> report :conditions first :iri))]
;;      [iri :sepio/has-qualifier moi]]))

;; (def evidence-level-label-to-concept
;;   {"Definitive" :sepio/DefinitiveEvidence
;;    "Limited" :sepio/LimitedEvidence
;;    "Moderate" :sepio/ModerateEvidence
;;    "No Reported Evidence" :sepio/NoEvidence
;;    "No Known Disease Relationship" :sepio/NoEvidence
;;    "Strong*" :sepio/StrongEvidence
;;    "Contradictory (disputed)" :sepio/DisputingEvidence
;;    "Strong" :sepio/StrongEvidence
;;    "Contradictory (refuted)" :sepio/RefutingEvidence
;;    "Refuted" :sepio/RefutingEvidence
;;    "Disputed" :sepio/DisputingEvidence
;;    "No Classification" :sepio/NoEvidence})

;; (def gci-sop-version 
;;   {"4" :sepio/ClinGenGeneValidityEvaluationCriteriaSOP4
;;    "5" :sepio/ClinGenGeneValidityEvaluationCriteriaSOP5
;;    "6" :sepio/ClinGenGeneValidityEvaluationCriteriaSOP6
;;    "7" :sepio/ClinGenGeneValidityEvaluationCriteriaSOP7
;;    "8" :sepio/ClinGenGeneValidityEvaluationCriteriaSOP8})

;; (defn contribution [report iri]
;;   [[iri :bfo/realizes :sepio/ApproverRole]
;;    [iri :sepio/has-agent  (resource (str affiliation-root
;;                                          (-> report :affiliation :id)))]
;;    [iri :sepio/activity-date (report-date report)]])

;; (def contributor-roles
;;   {"secondary contributor" :sepio/SecondaryContributorRole})

;; (defn secondary-contributions [report assertion-iri]
;;   (let [roles (set (keys contributor-roles))
;;         secondary-contributions (filter
;;                                  #(roles (:role %))
;;                                  (get-in report
;;                                          [:scoreJson :summary :contributors]))]
;;     (mapcat (fn [contribution]
;;               (let [contribution-iri (l/blank-node)
;;                     role (contributor-roles (:role contribution))]
;;                 [[assertion-iri
;;                   :sepio/qualified-contribution
;;                   contribution-iri]
;;                  [contribution-iri :bfo/realizes role]
;;                  [contribution-iri
;;                   :sepio/has-agent
;;                   (resource (str affiliation-root (:id contribution)))]
;;                  ]))
;;             secondary-contributions)))

;; (defn evidence-level-assertion [report iri id]
;;   (let [prop-iri (resource (str gci-root "proposition_" (:iri report)))
;;         contribution-iri (l/blank-node)]
;;     (concat [[iri :rdf/type :sepio/GeneValidityEvidenceLevelAssertion]
;;              [iri :sepio/has-subject prop-iri]
;;              [iri :sepio/has-predicate :sepio/HasEvidenceLevel]
;;              [iri :sepio/has-object (evidence-level-label-to-concept
;;                                      (-> report :scoreJson :summary :FinalClassification))]
;;              [iri :sepio/qualified-contribution contribution-iri]
;;              [iri :sepio/is-specified-by (gci-sop-version (or (:selectedSOPVersion report)
;;                                                               (:sopVersion report)))]
;;              [iri :dc/has-format (gci-sop-version (:sopVersion report))]]
;;             (secondary-contributions report iri)
;;             (validity-proposition report prop-iri)
;;             (contribution report contribution-iri))))

(defn json-content-node [report iri]
  [[iri :rdf/type :cnt/ContentAsText]
   [iri :cnt/chars (json/encode report)]])

(defn gci-legacy-report-to-triples [report]
  (let [root-version (str gci-root (-> report :iri))
        id (str (-> report :iri) "-" (s/replace (report-date report) #":" ""))
        iri (resource (str gci-root "report_" id))
        content-id (l/blank-node)
        assertion-id (resource (str gci-root "assertion_" id))
        result (concat [[iri :bfo/has-part content-id]
                        [iri :bfo/has-part assertion-id]]
                       (json-content-node report content-id))]
    result)) 

(defmethod transform-doc :gci-legacy-report-only [doc-def]
  (let [report-json (json/parse-string (:document doc-def) true)]
    (l/statements-to-model (gci-legacy-report-to-triples report-json))))

(defmethod add-model :gci-legacy-report-only [event]
  (log/debug :fn :add-model :format :gci-legacy :event event :msg :received-event)
  (let [report-json (json/parse-string (:genegraph.sink.event/value event) true)]
    (if (spec/valid? ::curation report-json)
      (assoc event
             ::q/model
             (l/statements-to-model  (gci-legacy-report-to-triples report-json)))
      (assoc event ::spec/invalid true))))
  
