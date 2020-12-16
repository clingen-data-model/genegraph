(ns genegraph.transform.dosage-jira
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as s]
            [flatland.ordered.map :refer [ordered-map]]
            [camel-snake-kebab.core :refer :all]
            [genegraph.database.query :as q]
            [genegraph.database.load :as l]
            [genegraph.transform.types :refer [add-model]]
            [clojure.spec.alpha :as spec])
  (:import java.time.Instant
           java.time.OffsetDateTime))

(def evidence-levels {"3" :sepio/DosageSufficientEvidence
                      "2" :sepio/DosageModerateEvidence
                      "1" :sepio/DosageMinimalEvidence
                      "0" :sepio/DosageNoEvidence
                      "30: Gene associated with autosomal recessive phenotype"
                      ;;(q/resource "http://purl.obolibrary.org/obo/SEPIO_0002502")
                      :sepio/GeneAssociatedWithAutosomalRecessivePhenotype
                      ;; assume moderate evidence for dosage sensitivity unlikely
                      "40: Dosage sensitivity unlikely" :sepio/DosageSufficientEvidence})

(spec/def ::status #(= "Closed" (:name %)))

(spec/def ::resolutiondate string?)

(spec/def ::resolution #(= "Complete" (:name %)))

(spec/def ::fields (spec/keys :req-un [::resolutiondate 
                                       ::status
                                       ::resolution]))

(def cg-prefix "http://dx.clinicalgenome.org/entities/")
(def region-prefix (str cg-prefix "region-"))

(def chr-to-ref {:grch37 {"1" "https://www.ncbi.nlm.nih.gov/nucore/NC_000001.10"
                          "2" "https://www.ncbi.nlm.nih.gov/nucore/NC_000002.11"
                          "3" "https://www.ncbi.nlm.nih.gov/nucore/NC_000003.11"
                          "4" "https://www.ncbi.nlm.nih.gov/nucore/NC_000004.11"
                          "5" "https://www.ncbi.nlm.nih.gov/nucore/NC_000005.9"
                          "6" "https://www.ncbi.nlm.nih.gov/nucore/NC_000006.11"
                          "7" "https://www.ncbi.nlm.nih.gov/nucore/NC_000007.13"
                          "8" "https://www.ncbi.nlm.nih.gov/nucore/NC_000008.10"
                          "9" "https://www.ncbi.nlm.nih.gov/nucore/NC_000009.11"
                          "10" "https://www.ncbi.nlm.nih.gov/nucore/NC_000010.10"
                          "11" "https://www.ncbi.nlm.nih.gov/nucore/NC_000011.9"
                          "12" "https://www.ncbi.nlm.nih.gov/nucore/NC_000012.11"
                          "13" "https://www.ncbi.nlm.nih.gov/nucore/NC_000013.10"
                          "14" "https://www.ncbi.nlm.nih.gov/nucore/NC_000014.8"
                          "15" "https://www.ncbi.nlm.nih.gov/nucore/NC_000015.9"
                          "16" "https://www.ncbi.nlm.nih.gov/nucore/NC_000016.9"
                          "17" "https://www.ncbi.nlm.nih.gov/nucore/NC_000017.10"
                          "18" "https://www.ncbi.nlm.nih.gov/nucore/NC_000018.9"
                          "19" "https://www.ncbi.nlm.nih.gov/nucore/NC_000019.9"
                          "20" "https://www.ncbi.nlm.nih.gov/nucore/NC_000020.10"
                          "21" "https://www.ncbi.nlm.nih.gov/nucore/NC_000021.8"
                          "22" "https://www.ncbi.nlm.nih.gov/nucore/NC_000022.10"
                          "X" "https://www.ncbi.nlm.nih.gov/nucore/NC_000023.10"
                          "Y" "https://www.ncbi.nlm.nih.gov/nucore/NC_000024.9"}
                 :grch38 {"1" "https://www.ncbi.nlm.nih.gov/nucore/NC_000001.11"
                          "2" "https://www.ncbi.nlm.nih.gov/nucore/NC_000002.12"
                          "3" "https://www.ncbi.nlm.nih.gov/nucore/NC_000003.12"
                          "4" "https://www.ncbi.nlm.nih.gov/nucore/NC_000004.12"
                          "5" "https://www.ncbi.nlm.nih.gov/nucore/NC_000005.10"
                          "6" "https://www.ncbi.nlm.nih.gov/nucore/NC_000006.12"
                          "7" "https://www.ncbi.nlm.nih.gov/nucore/NC_000007.14"
                          "8" "https://www.ncbi.nlm.nih.gov/nucore/NC_000008.11"
                          "9" "https://www.ncbi.nlm.nih.gov/nucore/NC_000009.12"
                          "10" "https://www.ncbi.nlm.nih.gov/nucore/NC_000010.11"
                          "11" "https://www.ncbi.nlm.nih.gov/nucore/NC_000011.10"
                          "12" "https://www.ncbi.nlm.nih.gov/nucore/NC_000012.12"
                          "13" "https://www.ncbi.nlm.nih.gov/nucore/NC_000013.11"
                          "14" "https://www.ncbi.nlm.nih.gov/nucore/NC_000014.9"
                          "15" "https://www.ncbi.nlm.nih.gov/nucore/NC_000015.10"
                          "16" "https://www.ncbi.nlm.nih.gov/nucore/NC_000016.10"
                          "17" "https://www.ncbi.nlm.nih.gov/nucore/NC_000017.11"
                          "18" "https://www.ncbi.nlm.nih.gov/nucore/NC_000018.10"
                          "19" "https://www.ncbi.nlm.nih.gov/nucore/NC_000019.10"
                          "20" "https://www.ncbi.nlm.nih.gov/nucore/NC_000020.11"
                          "21" "https://www.ncbi.nlm.nih.gov/nucore/NC_000021.9"
                          "22" "https://www.ncbi.nlm.nih.gov/nucore/NC_000022.11"
                          "X" "https://www.ncbi.nlm.nih.gov/nucore/NC_000023.11"
                          "Y" "https://www.ncbi.nlm.nih.gov/nucore/NC_000024.10"}})

(def build-location {:grch38 :customfield-10532
                     :grch37 :customfield-10160}) 



(defn- -format-jira-datetime-string
  "Corrects flaw in JIRA's formatting of datetime strings. By default JIRA does not
  include a colon in the offset, which is incompatible with standard java.util.time
  libraries. This inserts an appropriate offset with a regex"
  [s]
  (s/replace s #"(\d\d)(\d\d)$" "$1:$2"))

;; TODO Java chokes on parsing a time offset without a colon, and is unwilling
;; to construct a ISO_INSTANT from the format parsed below. Need to either hack
;; a colon in the datetime or understand better how Java is doing things
(defn- time-str-offset-to-instant [s]
  ;; "2018-03-27T09:55:41.000-0400"
  (->> s
       -format-jira-datetime-string
       OffsetDateTime/parse
       Instant/from
       str))

(defn- resolution-date [interp]
  (when-let [resolution-date (get-in interp [:fields :resolutiondate])]
    (time-str-offset-to-instant resolution-date)))

(defn- updated-date [interp]
  (when-let [updated (get-in interp [:fields :updated])]
    (time-str-offset-to-instant updated)))

(defn- gene-iri [curation]
  (when-let [gene (get-in curation [:fields :customfield-10157])]
    (q/resource gene)))

(defn- region-iri [curation]
  (q/resource (str region-prefix (:key curation))))

(defn- subject-iri [curation]
  (if-let [gene (gene-iri curation)]
    gene
    (region-iri curation)))

(defn- sequence-location [curation build]
  (when-let [loc-str (get-in curation [:fields (build-location build)])]
    (let [[_ chr start-coord end-coord] (re-find #"(\w+):(.+)-(.+)$" loc-str)
          iri (l/blank-node)
          interval-iri (l/blank-node)
          reference-sequence (get-in chr-to-ref 
                                     [build 
                                      (subs chr 3)])]
      [iri [[iri :rdf/type :geno/SequenceFeatureLocation]
            ;; TODO reference sequence should be a resource
            [iri :geno/has-reference-sequence reference-sequence]
            [iri :geno/has-interval interval-iri]
            [interval-iri :rdf/type :geno/SequenceInterval]
            [interval-iri :geno/start-position (-> start-coord (s/replace #"\D" "") Integer.)]
            [interval-iri :geno/end-position (-> end-coord (s/replace #"\D" "") Integer.)]]])))

(defn- location [curation]
  (let [iri (region-iri curation)
        locations (->> (keys build-location)
                       (map #(sequence-location curation %))
                       (remove nil?))]
    (concat (map (fn [l] [iri :geno/has-location (first l)]) locations)
            (mapcat second locations)
            [[iri :rdfs/label (get-in curation [:fields :customfield-10202])]
             [iri :rdf/type :so/SequenceFeature]])))

(defn- topic [report-iri curation]
  (if-let [gene (gene-iri curation)]
    [[report-iri :iao/is-about gene]]
    (conj (location curation)
     [report-iri :iao/is-about (region-iri curation)])))

(defn- contribution-iri
  [curation]
  (q/resource (str cg-prefix "contribution-" (:key curation)  "-" (updated-date curation))))

(defn- contribution
  [iri curation]
  [[iri :sepio/activity-date (resolution-date curation)]
   [iri :bfo/realizes :sepio/InterpreterRole]])

(defn- assertion-iri [curation dosage]
  (q/resource (str cg-prefix (:key curation) "x" dosage "-" (updated-date curation))))

(defn- proposition-iri [curation dosage]
  (q/resource (str cg-prefix (:key curation) "x" dosage)))

(def evidence-field-map
  {1 [[:customfield-10183 :customfield-10184]
      [:customfield-10185 :customfield-10186]
      [:customfield-10187 :customfield-10188]]
   3 [[:customfield-10189 :customfield-10190]
      [:customfield-10191 :customfield-10192]
      [:customfield-10193 :customfield-10194]]})

(defn- finding-data [curation dosage]
  (->> (get evidence-field-map dosage)
       (map (fn [row] 
         (map #(get-in curation [:fields %]) row)))
       (remove #(nil? (first %)))))

(defn- study-findings [assertion-iri curation dosage]
  (let [findings (finding-data curation dosage)]
    (mapcat (fn [[pmid description]]
              (let [finding-iri (l/blank-node)]
                [[assertion-iri :sepio/has-evidence-line-with-item finding-iri]
                 [finding-iri :rdf/type :sepio/StudyFinding]
                 [finding-iri :dc/source (str "PMID:" (re-find #"\d+" pmid))]
                 [finding-iri :dc/description (or description "")]]))
            findings)))

(defn- dosage-proposition-object [curation dosage]
  (let [object-field (if (= 1 dosage) :customfield-10200 :customfield-10201)
        phenotype-str (get-in curation [:fields object-field])
        iri (proposition-iri curation dosage)]
    (if phenotype-str
      (map #(vector iri :sepio/has-object %)
           (->> (s/split phenotype-str #",")
                (map #(q/resource (str "http://identifiers.org/omim/" (s/trim %))))))
      [[iri :sepio/has-object (q/resource "http://purl.obolibrary.org/obo/MONDO_0000001")]])))

(defn- gene-dosage-variant [iri curation dosage]
  [[iri :rdf/type :geno/FunctionalCopyNumberComplement]
   [iri :geno/has-member-count dosage]
   [iri :geno/has-location (subject-iri curation)]])

(defn- proposition-predicate [curation dosage]
  (if (and (= 1 dosage)
           (= "40: Dosage sensitivity unlikely" 
              (get-in curation [:fields :customfield-10165 :value])))
    :geno/BenignForCondition
    :geno/PathogenicForCondition))

(defn- proposition [curation dosage]
  (let [iri (proposition-iri curation dosage)
        variant-iri (l/blank-node)]
    (concat [[iri :rdf/type :sepio/DosageSensitivityProposition]
             [iri :sepio/has-predicate (proposition-predicate curation dosage)]
             [iri :sepio/has-subject variant-iri]]
            (dosage-proposition-object curation dosage)
            (gene-dosage-variant variant-iri curation dosage))))

(defn- dosage-assertion-value [curation dosage]
  (let [assertion-field (if (= 1 dosage) :customfield-10165 :customfield-10166)]
      (evidence-levels (get-in curation [:fields assertion-field :value]))))

(defn- dosage-assertion-description [curation dosage]
  (let [description-field (if (= 1 dosage) :customfield-10198 :customfield-10199)]
    (or (get-in curation [:fields description-field :value]) "")))

(defn- common-assertion-fields
  [iri curation dosage]
  []
  (concat [[iri :sepio/is-specified-by :sepio/DosageSensitivityEvaluationGuideline]
           [iri :sepio/qualified-contribution (contribution-iri curation)]
           [iri :sepio/has-subject (proposition-iri curation dosage)]
           [iri :dc/description (dosage-assertion-description curation dosage)]]
          (study-findings iri curation dosage)
          (proposition curation dosage)))

(defn- evidence-strength-assertion [curation dosage]
  (let [iri (assertion-iri curation dosage)]
    (concat (common-assertion-fields iri curation dosage)
            [[iri :rdf/type :sepio/EvidenceLevelAssertion]
             [iri :sepio/has-predicate :sepio/HasEvidenceLevel]
             [iri :sepio/has-object (dosage-assertion-value curation dosage)]])))

(defn- scope-assertion
  [curation dosage]
  (let [iri (assertion-iri curation dosage)]
    (concat (common-assertion-fields iri curation dosage)
            [[iri :sepio/has-predicate :sepio/DosageScopeAssertion]
             [iri :sepio/has-object :sepio/GeneAssociatedWithAutosomalRecessivePhenotype]
             [iri :rdf/type :sepio/PropositionScopeAssertion]])))

(defn- base-iri [curation]
  (str cg-prefix (:key curation)))

(defn- report-iri [curation]
  (q/resource (str (base-iri curation) "-" (updated-date curation))))

(defn- assertion [curation dosage]
  (if (dosage-assertion-value curation dosage)
    (conj 
     (if (and (= 1 dosage)
              (= "30: Gene associated with autosomal recessive phenotype" 
                 (get-in curation [:fields :customfield-10165 :value])))
       (scope-assertion curation dosage)
       (evidence-strength-assertion curation dosage))
     [(report-iri curation) :bfo/has-part (assertion-iri curation dosage)])
    []))

(defn gene-dosage-report
  [curation]
  (let [base-iri (str cg-prefix (:key curation))
        report-iri (report-iri curation)
        contribution-iri (contribution-iri curation)
        result (concat [[report-iri :rdf/type :sepio/GeneDosageReport]
                        [report-iri :dc/is-version-of (q/resource base-iri)]
                        [report-iri :sepio/qualified-contribution contribution-iri]
                        [base-iri :rdf/type :sepio/GeneDosageRecord]]
                       (contribution contribution-iri curation)
                       (assertion curation 1)
                       (assertion curation 3)
                       (topic report-iri curation))]
    result))

(defmethod add-model :gene-dosage-jira [event]
  (let [jira-json (json/parse-string (:genegraph.sink.event/value event) ->kebab-case-keyword)]
    ;; (clojure.pprint/pprint (gene-dosage-report jira-json))
    (if (spec/invalid? (spec/conform ::fields (:fields jira-json)))
      (assoc event ::spec/invalid true)
      (assoc event ::q/model (-> jira-json gene-dosage-report l/statements-to-model)))))
