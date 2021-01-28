(ns genegraph.transform.actionability
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [io.pedestal.log :as log]))

(spec/def :condition/iri #(or (re-matches #"http://purl\.obolibrary\.org/obo/OMIM_\d+" %)
                              (re-matches #"http://purl\.obolibrary\.org/obo/MONDO_\d+" %)))

(spec/def ::iri #(re-find #"^https://actionability\.clinicalgenome\.org/ac" %))

(spec/def ::gene #(re-matches #"HGNC:\d+" %))

(spec/def ::statusFlag #(#{"Released" "Released - Under Revision" "Retracted"} %))

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

(def vocab
  {"Definitive Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003535"
   "Strong Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003536"
   "Moderate Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003537"
   "Limited Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003538"
   "Insufficient Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003539"
   "Insufficient Evidence" "http://purl.obolibrary.org/obo/SEPIO_0003539"
   "No Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003540"
   "Assertion Pending" "http://purl.obolibrary.org/obo/SEPIO_0003541"
   
   "Pediatric AWG" "http://dataexchange.clinicalgenome.org/terms/PediatricActionabilityWorkingGroup"
   "Adult AWG" "http://dataexchange.clinicalgenome.org/terms/AdultActionabilityWorkingGroup"})

(defn genetic-condition-label [parent-condition gene]
  (str (q/ld1-> parent-condition [:rdfs/label]) ", " (q/ld1-> gene [:skos/preferred-label])))

(defn gene-resource [gene-str]
  (q/ld1-> (q/resource gene-str) [[:owl/same-as :<]]))

(defn genetic-condition [curation-iri condition]
  (if-let [condition-resource (if (re-find #"MONDO" (:iri condition))
                                  (q/resource (:iri condition))
                                  (first (filter #(re-find #"MONDO" (str %))
                                                 (q/ld-> (q/resource (:curie condition))
                                                         [[:skos/has-exact-match :-]]))))]
    (let [gc-node (l/blank-node)
          gene (gene-resource (:gene condition))]
      [[curation-iri :sepio/is-about-condition gc-node]
       [gc-node :rdf/type :sepio/GeneticCondition]
       [gc-node :rdf/type :cg/ActionabilityGeneticCondition]
       [gc-node :rdfs/sub-class-of condition-resource]
       [gc-node :sepio/is-about-gene gene]
       [gc-node :rdfs/label (genetic-condition-label condition-resource gene)]])
    nil))

(defn search-contributions [curation-iri search-date agent-iri]
  (let [contrib-iri (l/blank-node)]
    [[curation-iri :sepio/qualified-contribution contrib-iri]
     [contrib-iri :sepio/activity-date search-date]
     [contrib-iri :bfo/realizes :sepio/EvidenceRole]
     [contrib-iri :sepio/has-agent agent-iri]]))

(defn is-preferred-condition [curation condition]
  (let [preferred-conditions
        (->> (:preferred_conditions curation)
             (map #(vector (:iri %) (:gene %)))
             (into #{}))]
    (preferred-conditions [(:iri condition) (:gene condition)])))

(defn assertion [curation assertion-map]
  (let [assertion-iri (l/blank-node)
        curation-iri (:iri curation)
        preferred-condition
        (if (is-preferred-condition curation assertion-map)
          [[assertion-iri :rdf/type :cg/ActionabilityAssertionForPreferredCondition]]
          [])]
    (concat
     [[curation-iri :bfo/has-part assertion-iri]
      [assertion-iri :rdf/type :sepio/ActionabilityAssertion]
      [assertion-iri :sepio/has-subject (-> assertion-map :gene gene-resource)]
      [assertion-iri :sepio/has-predicate (-> assertion-map :assertion vocab q/resource)]
      [assertion-iri :sepio/has-object (-> assertion-map :iri q/resource)]]
     preferred-condition)))

(defn total-scores [curation]
  (->> curation
       :scores
       (map :ScoringGroups)
       flatten
       (map :Interventions)
       flatten
       (map :ScoringGroups)
       flatten
       (map :Total)
       flatten
       (map #(re-find #"\d+" %))
       (map #(Integer/parseInt %))
       (map #(vector (:iri curation) :cg/has-total-actionability-score %))))

(defn assertions [curation]
  (let [assertion-set
        (cond 
          (:assertions curation)
          (into #{} (:assertions curation))
          
          (= "Failed" (:earlyRuleOutStatus curation))
          (into #{} (map #(assoc % :assertion "Insufficient Evidence")
                         (:conditions curation)))
          :else
          (into #{} (map #(assoc % :assertion "Assertion Pending")
                         (:conditions curation))))]
    (->> assertion-set
         (mapcat #(assertion curation %)))))

(defn transform [curation]
  (let [statements (if (spec/valid? ::curation curation)
                     (let [curation-iri (:iri curation)
                           contrib-iri (l/blank-node)
                           agent-iri (-> curation :affiliations first :id vocab q/resource)]
                       (concat 
                        [[curation-iri :rdf/type :sepio/ActionabilityReport]
                         [curation-iri :sepio/qualified-contribution contrib-iri]
                         [curation-iri :dc/source (:scoreDetails curation)]
                         [contrib-iri :sepio/activity-date (:dateISO8601 curation)]
                         [contrib-iri :bfo/realizes :sepio/ApproverRole]
                         [contrib-iri :sepio/has-agent agent-iri]
                         ;; [agent-iri :rdfs/label (-> curation :affiliations first :name)]
                         ]
                        (mapcat #(genetic-condition curation-iri %) (:conditions curation))
                        (mapcat #(search-contributions curation-iri % agent-iri)
                                (:searchDates curation))
                        (assertions curation)
                        (total-scores curation)))
                     [])] 
    (l/statements-to-model statements)))

(defmethod transform-doc :actionability-v1 [doc-def]
  (let [doc (or (:document doc-def) (slurp (src-path doc-def)))]
    (transform (json/parse-string doc true))))


(defmethod add-model :actionability-v1 [event]
  (log/debug :fn :add-model :format :actionability-v1 :event event :msg :received-event)
  (assoc event
         ::q/model
         (transform (json/parse-string (:genegraph.sink.event/value event) true))))

