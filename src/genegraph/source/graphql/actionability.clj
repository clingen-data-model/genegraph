;; Functions in this namespace are DEPRECATED

(ns genegraph.source.graphql.actionability
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as str]
            [io.pedestal.log :as log]))

(defresolver actionability-query [args value]
  (q/resource (:iri args)))

(def report-date-query 
  (q/create-query 
   (str "select ?contribution where "
        " { ?report :sepio/qualified-contribution ?contribution . "
        "   ?contribution :bfo/realizes :sepio/EvidenceRole . "
        "   ?contribution :sepio/activity-date ?date } "
        " order by desc(?date) "
        " limit 1 ")))

(defresolver ^:expire-by-value report-date [args value]
  (some-> (report-date-query {:report value}) first :sepio/activity-date first))

(defresolver ^:expire-by-value report-id [args value]
  (->> value str (re-find #"\w+$")))

(defresolver ^:expire-by-value wg-label [args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/has-agent :rdfs/label]))

(defresolver classification-description [args value]
  "View report for scoring details")

(defresolver ^:expire-by-value conditions [args value]
  (:sepio/is-about-condition value))

(defresolver ^:expire-by-value source [args value]
  (q/ld1-> value [:dc/source]))

(def wg-search-actionability-reports
  (q/create-query (str "select ?qc where { ?s a :sepio/ActionabilityReport . "
                       "?s :sepio/qualified-contribution ?qc . "
                       "?qc :bfo/realizes :sepio/EvidenceRole ."
                       "?qc :sepio/has-agent ?agent . }"
                       )))

(defn statistics-query [context args value]
  1)

(defn tot-actionability-reports [context args value]
  ((q/create-query "select ?s where { ?s a :sepio/ActionabilityReport }" {::q/distinct false})
   {::q/params {:type :count}}))

(defn tot-actionability-updated-reports [context args value]
  (let [updated-reports-query (str "select ?s where { ?s a :sepio/ActionabilityReport . "
                                  "?s :dc/has-version ?v . "
                                  "FILTER regex(?v, '[2-9].[0-9].[0-9]') }")]
  ((q/create-query updated-reports-query {::q/distinct false}) {::q/params {:type :count}})))

(def uniq-disease-pairs (q/create-query (str "select ?gene where { "
                             "?part a :cg/ActionabilityAssertionForPreferredCondition . "
                             "?part :sepio/has-object ?disease . "
                             "?part :sepio/has-subject ?gene . "
                             "?s :bfo/has-part ?part . "
                             "?s a :sepio/ActionabilityReport . "
                             "?s :sepio/qualified-contribution ?qc . "
                             "?qc :sepio/has-agent ?wg } "
                             "GROUP BY ?gene ?disease ") {::q/distinct false}))

(defn tot-gene-disease-pairs [context args value]
  (uniq-disease-pairs {::q/params {:type :count}}))

(defn tot-adult-gene-disease-pairs [context args value]
  (uniq-disease-pairs {::q/params {:type :count} :wg :cg/AdultActionabilityWorkingGroup}))

(defn tot-pediatric-gene-disease-pairs [context args value]
  (uniq-disease-pairs {::q/params {:type :count} :wg :cg/PediatricActionabilityWorkingGroup}))

(def score-counts (q/create-query (str "select ?s where { "
                       "?s a :sepio/ActionabilityReport . "
                       "?s :sepio/qualified-contribution ?qc . "
                       "?qc :bfo/realizes :sepio/ApproverRole . "
                       "?qc :sepio/has-agent ?wg }") {::q/distinct false}))

(defn score-counts-report [wg]
  (let [records (score-counts {:wg wg})]
    (doseq [rec records]
      (let [scores (q/ld-> rec [ :cg/has-total-actionability-score])
            contexts (q/ld-> rec [:sepio/qualified-contribution :sepio-has-agent :rdfs/label])
            genes (into #{} (q/ld-> rec [:bfo/has-part :sepio/has-subject :skos/preferred-label]))
            diseases (into #{} (q/ld-> rec [:sepio/is-about-condition :rdfs/label]))
            dates (into #{} (q/ld-> rec [:sepio/qualified-contribution :sepio/activity-date]))]
        (doseq [score scores]
          (let [msg (str "iri:" (str rec) "\tscore:" score "\tcontexts:" wg "\tgenes:" (str/join "," genes) "\tdiseases:" diseases "\tdates:" (str/join "," dates) "\n")]
            (spit "out.txt" msg :append true)))))))
        
        

(defn tot-wg-score-counts [wg]
  (let [records (if (some? wg)
                  (score-counts {:wg wg})
                  (score-counts))
        counts (->> records
                    (mapcat #(q/ld-> % [ :cg/has-total-actionability-score ]))
                    frequencies
                    sort)]
    counts))

(defn tot-adult-score-counts [context args value]
  (str/join " " (map #(str/join "=" %) (tot-wg-score-counts :cg/AdultActionabilityWorkingGroup))))

(defn tot-pediatric-score-counts [context args value]
  (str/join " " (map #(str/join "=" %) (tot-wg-score-counts :cg/PediatricActionabilityWorkingGroup))))

(defn tot-outcome-intervention-pairs [context args value]
  (->> (tot-wg-score-counts nil)
       (map second)
       (reduce +)))

(defn tot-adult-outcome-intervention-pairs [context args value]
  (->> (tot-wg-score-counts  :cg/AdultActionabilityWorkingGroup)
       (map second)
       (reduce +)))

(defn tot-pediatric-outcome-intervention-pairs [context args value]
  (->> (tot-wg-score-counts :cg/PediatricActionabilityWorkingGroup)
       (map second)
       (reduce +)))

(def rule-out (q/create-query (str "select ?p where { "
                       "?s a :sepio/ActionabilityReport . "
                       "?s :bfo/has-part ?p . "
                       "?p :sepio/has-predicate :sepio/InsufficientEvidenceForActionabilityEarlyRuleOut . "
                       "?s :sepio/qualified-contribution ?qc . "
                       "?qc :sepio/has-agent ?wg }") {::q/distinct false}))

(defn tot-adult-failed-early-rule-out [context args value]
  (rule-out {::q/params {:type :count} :wg :cg/AdultActionabilityWorkingGroup}))

(defn tot-pediatric-failed-early-rule-out [context args value]
  (rule-out {::q/params {:type :count} :wg :cg/PediatricActionabilityWorkingGroup}))
