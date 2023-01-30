(ns genegraph.source.graphql.gene-validity
  (:require [genegraph.database.query :as q :refer [ld-> ld1-> create-query resource]]
            [genegraph.source.graphql.common.enum :as enum]
            [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as s]
            [cheshire.core :as json]))

;; CGGV:assertion_43fb4e99-e97a-4d9c-af11-79c2b09ecd2e-2019-07-24T160000.000Z
;; CGGCIEX:assertion_10075
;; https://search.clinicalgenome.org/kb/gene-validity/3210
;; "https://search.clinicalgenome.org/kb/gene-validity/CGGV:assertion_2f6d71c6-6595-49bf-a50e-fce726b22088-2018-10-03T160000.000Z"

(defn find-newest-gci-curation [id]
  (when-let [uuid-part (->> id (re-find #"(\w+_)(\w+-\w+-\w+-\w+-\w+)") last)]
    (let [proposition (q/resource (str "CGGV:proposition_" uuid-part))]
      (when (q/is-rdf-type? proposition :sepio/GeneValidityProposition)
        (ld1-> proposition [[:sepio/has-subject :<]])))))

(defn find-gciex-curation [id]
  (when (re-matches #"\d+" id)
    (let [gciex-assertion (q/resource (str "CGGCIEX:assertion_" id))]
      (when (q/is-rdf-type? gciex-assertion :sepio/GeneValidityEvidenceLevelAssertion)
        gciex-assertion))))

(defresolver gene-validity-assertion-query [args value]
  (let [requested-assertion (q/resource (:iri args))]
    (if (q/is-rdf-type? requested-assertion :sepio/GeneValidityEvidenceLevelAssertion)
      requested-assertion
      (or (q/ld1-> requested-assertion [[:cg/website-legacy-id :<]])
          (find-newest-gci-curation (:iri args))
          (find-gciex-curation (:iri args))))))

(defresolver ^:expire-by-value report-date [args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))


;; DEPRECATED
(defresolver gene-validity-list [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))]
    (curation/gene-validity-curations {::q/params params})))

(defresolver ^:expire-always gene-validity-curations [args value]
  (curation/gene-validity-curations-for-resolver args value))

;; DEPRECATED -- may not be used at all
(defresolver criteria [args value]
  nil)

(def evidence-levels
  {:sepio/DefinitiveEvidence :DEFINITIVE
   :sepio/LimitedEvidence :LIMITED
   :sepio/ModerateEvidence :MODERATE
   :sepio/NoEvidence :NO_KNOWN_DISEASE_RELATIONSHIP
   :sepio/RefutingEvidence :REFUTED
   :sepio/DisputingEvidence :DISPUTED
   :sepio/StrongEvidence :STRONG})

(defresolver ^:expire-by-value classification [args value]
  (-> value :sepio/has-object first))

(defresolver ^:expire-by-value gene [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-subject]))

(defresolver ^:expire-by-value disease [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-object]))

(defresolver ^:expire-by-value mode-of-inheritance [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-qualifier]))

;; (defresolver attributed-to [args value]
  
;;   (ld1-> value [:sepio/qualified-contribution :sepio/has-agent]))

(def primary-attribution-query
  (q/create-query
   "select ?agent where {
    ?assertion :sepio/qualified-contribution ?contribution . 
    ?contribution :bfo/realizes :sepio/ApproverRole ;
    :sepio/has-agent ?agent . } 
   limit 1 "))

(defresolver ^:expire-by-value attributed-to [args value]
  (first (primary-attribution-query {:assertion value})))

(defresolver ^:expire-by-value contributions [args value]
  (:sepio/qualified-contribution value))

(defresolver ^:expire-by-value specified-by [args value]
  ;; this returns a resource
  (ld1-> value [:sepio/is-specified-by]))

(defresolver ^:expire-by-value has-format [args value]
  ;; this returns a string
  (ld1-> value [:sepio/is-specified-by]))

(defn legacy-json [_ _ value]
  (ld1-> value [[:bfo/has-part :<] :bfo/has-part :cnt/chars]))


;; TODO should be able to remove first part after
;; releasing full GCI
(defresolver ^:expire-by-value report-id [_ value]
  (let [curie (q/curie value)]
    ;; match vintage style curie with date time stamp at the end
    (if (re-find #"\.\d{3}Z$" curie)
      (-> (legacy-json nil nil value)
          (json/parse-string true)
          :report_id)
      ;; match GCI Express is always nil
      (if (re-find #"^CGGCIEX:assertion_\d+$" curie)
        nil
        ;; match gci refactor
        (when-let [proposition-id (-> (ld1-> value [:sepio/has-subject])
                                      str
                                      (s/split #"/")
                                      last)]
          (re-find #"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$"
                   proposition-id))))))
              
(defresolver ^:expire-by-value animal-model [args value]
  (let [res (first (q/select "select ?s where {
                                  ?s :bfo/has-part ?resource ;
                                  a :sepio/GeneValidityReport ;
                                  :cg/is-animal-model-only ?animal }"
                             {:resource value}))]
    (if res
      (case (ld1-> res [:cg/is-animal-model-only])
        "YES" true
        "NO"  false
        nil)
      nil)))

