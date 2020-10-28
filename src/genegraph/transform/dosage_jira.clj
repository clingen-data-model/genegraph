(ns genegraph.transform.dosage-jira
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as s]
            [flatland.ordered.map :refer [ordered-map]]
            [camel-snake-kebab.core :refer :all]
            [genegraph.database.query :as q]
            [genegraph.database.load :as l])
  (:import java.time.Instant
           java.time.OffsetDateTime))

(def context (into (ordered-map) [["id" "@id"]
                                  ["type" "@type"]
                                  ["SEPIO" "http://purl.obolibrary.org/obo/SEPIO_"]
                                  ["PMID" "https://www.ncbi.nlm.nih.gov/pubmed/"]
                                  ["BFO" "http://purl.obolibrary.org/obo/BFO_"]
                                  ["CG" "http://dataexchange.clinicalgenome.org/terms/"]
                                  ["DC" "http://purl.org/dc/elements/1.1/"]
                                  ["OMIM" "http://identifiers.org/omim/"]
                                  ["MONDO" "http://purl.obolibrary.org/obo/MONDO_"]
                                  ["FALDO" "http://biohackathon.org/resource/faldo#"]
                                  ["NCBI_NU" "https://www.ncbi.nlm.nih.gov/nuccore/"]
                                  ["RDFS" "http://www.w3.org/2000/01/rdf-schema#"]
                                  ["GENO" "http://purl.obolibrary.org/obo/GENO_"]
                                  ["IAO" "http://purl.obolibrary.org/obo/IAO_"]
                                  ["DCT" "http://purl.org/dc/terms/"]
                                  ["SO" "http://purl.obolibrary.org/obo/SO_"]
                                  ["has_evidence_with_item" {"@id" "SEPIO:0000189"
                                                             "@type" "@id"}]
                                  ["has_predicate" {"@id" "SEPIO:0000389"
                                                    "@type" "@id"}]
                                  ["has_subject" {"@id" "SEPIO:0000388"
                                                  "@type" "@id"}]
                                  ["has_object" {"@id" "SEPIO:0000390"
                                                 "@type" "@id"}]
                                  ["qualified_contribution" {"@id" "SEPIO:0000159" 
                                                             "@type" "@id"}]
                                  ["is_specified_by" {"@id" "SEPIO:0000041"
                                                      "@type" "@id"}]
                                  ["reference" {"@id" "FALDO:reference"
                                                "@type" "@id"}]
                                  ["realizes" {"@id" "BFO:0000055"
                                               "@type" "@id"}]
                                  ["source" {"@id" "DCT:source"
                                               "@type" "@id"}]
                                  ["is_feature_affected_by" {"@id" "GENO:0000445"
                                                             "@type" "@id"}]
                                  ["has_part" {"@id" "BFO:0000051"
                                               "@type" "@id"}]
                                  ["is_version_of" {"@id" "DCT:isVersionOf"
                                                    "@type" "@id"}]
                                  ["interval" {"@id" "GENO:0000966"
                                               "@type" "@id"}]
                                  ["has_location" {"@id" "GENO:0000903"
                                                   "@type" "@id"}]
                                  ["is_about" {"@id" "IAO:0000136"
                                               "@type" "@id"}]
                                  ["label" "RDFS:label"]
                                  ["activity_date" "SEPIO:0000160"]
                                  ["has_count" "GENO:0000917"]
                                  ["start" "GENO:0000894"]
                                  ["end" "GENO:0000895"]
                                  ["description" "DC:description"]
                                  ["sequence_id" {"@id" "GENO:0000967"
                                                  "@type" "@id"}]
                                  ["SimpleInterval" "GENO:0000965"]
                                  ["SequenceLocation" "GENO:0000815"]
                                  ["SequenceFeature" "SO:0000110"]]))

(def frontmatter-fields {"title" :title
                         "updated" :modified
                         "status" :status})

(def context-uri "http://dataexchange.clinicalgenome.org/contexts/sepio-context-v1")
(def cg-prefix "http://dx.clinicalgenome.org/entities/")
(def region-prefix (str cg-prefix "region-"))

(def chr-to-ref {:grch37 {"chr1" "NCBI_NU:NC_000001.10"
                          "chr2" "NCBI_NU:NC_000002.11"
                          "chr3" "NCBI_NU:NC_000003.11"
                          "chr4" "NCBI_NU:NC_000004.11"
                          "chr5" "NCBI_NU:NC_000005.9"
                          "chr6" "NCBI_NU:NC_000006.11"
                          "chr7" "NCBI_NU:NC_000007.13"
                          "chr8" "NCBI_NU:NC_000008.10"
                          "chr9" "NCBI_NU:NC_000009.11"
                          "chr10" "NCBI_NU:NC_000010.10"
                          "chr11" "NCBI_NU:NC_000011.9"
                          "chr12" "NCBI_NU:NC_000012.11"
                          "chr13" "NCBI_NU:NC_000013.10"
                          "chr14" "NCBI_NU:NC_000014.8"
                          "chr15" "NCBI_NU:NC_000015.9"
                          "chr16" "NCBI_NU:NC_000016.9"
                          "chr17" "NCBI_NU:NC_000017.10"
                          "chr18" "NCBI_NU:NC_000018.9"
                          "chr19" "NCBI_NU:NC_000019.9"
                          "chr20" "NCBI_NU:NC_000020.10"
                          "chr21" "NCBI_NU:NC_000021.8"
                          "chr22" "NCBI_NU:NC_000022.10"
                          "chrX" "NCBI_NU:NC_000023.10"
                          "chrY" "NCBI_NU:NC_000024.9"}
                 :grch38 {"chr1" "NCBI_NU:NC_000001.11"
                          "chr2" "NCBI_NU:NC_000002.12"
                          "chr3" "NCBI_NU:NC_000003.12"
                          "chr4" "NCBI_NU:NC_000004.12"
                          "chr5" "NCBI_NU:NC_000005.10"
                          "chr6" "NCBI_NU:NC_000006.12"
                          "chr7" "NCBI_NU:NC_000007.14"
                          "chr8" "NCBI_NU:NC_000008.11"
                          "chr9" "NCBI_NU:NC_000009.12"
                          "chr10" "NCBI_NU:NC_000010.11"
                          "chr11" "NCBI_NU:NC_000011.10"
                          "chr12" "NCBI_NU:NC_000012.12"
                          "chr13" "NCBI_NU:NC_000013.11"
                          "chr14" "NCBI_NU:NC_000014.9"
                          "chr15" "NCBI_NU:NC_000015.10"
                          "chr16" "NCBI_NU:NC_000016.10"
                          "chr17" "NCBI_NU:NC_000017.11"
                          "chr18" "NCBI_NU:NC_000018.10"
                          "chr19" "NCBI_NU:NC_000019.10"
                          "chr20" "NCBI_NU:NC_000020.11"
                          "chr21" "NCBI_NU:NC_000021.9"
                          "chr22" "NCBI_NU:NC_000022.11"
                          "chrX" "NCBI_NU:NC_000023.11"
                          "chrY" "NCBI_NU:NC_000024.10"}})

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

(defn- construct-study-finding
  [interp [pmid-field description-field]]
  (when-let [pmid (get-in interp [:fields pmid-field])]
    {:type "SEPIO:0000173"
     :source (str "PMID:" (re-find #"\d+" pmid))
     :description (get-in interp [:fields description-field])}))

;; Key is the dosage of the area (1: haploinsufficient, 3: triplosensitive)
;; Each evidence field is a tuple with the PMID first and the described evidence
;; second
(def evidence-field-map
  {1 [[:customfield-10183 :customfield-10184]
      [:customfield-10185 :customfield-10186]
      [:customfield-10187 :customfield-10188]]
   3 [[:customfield-10189 :customfield-10190]
      [:customfield-10191 :customfield-10192]
      [:customfield-10193 :customfield-10194]]})

(defn- construct-study-findings
  [interp dosage]
  (remove nil? (map #(construct-study-finding interp %) (evidence-field-map dosage))))

(defn- construct-sequence-location [interp build]
  (when-let [loc-str (get-in interp [:fields (build-location build)])]
    (let [[_ chr start-coord end-coord] (re-find #"(\w+):(.+)-(.+)$" loc-str)]
      {:type "SequenceLocation"
       :sequence-id (-> chr-to-ref
                        (get build)
                        (get chr))
       :interval {:type "SimpleInterval" ; sequence interval
                  :start (-> start-coord (s/replace #"\D" "") Integer.)
                  :end (-> end-coord (s/replace #"\D" "") Integer.)}})))

(defn- construct-location [interp]
  {:id (str region-prefix (:key interp))
   :label (get-in interp [:fields :customfield-10202])
   :type "SequenceFeature"
   :has-location (->> (keys build-location)
                      (map #(construct-sequence-location interp %))
                      (remove nil?))})

(defn- location-id [interp]
  (str region-prefix (:key interp)))

(defn- dosage-subject [interp]
  (if-let [gene (get-in interp [:fields :customfield-10157])]
    gene
    (construct-location interp)))

(defn- dosage-subject-id [interp]
  (if-let [gene (get-in interp [:fields :customfield-10157])]
    gene
    (location-id interp)))

(defn- construct-gene-dosage-variant
  "Construct the variant representing the stated dosage of a gene"
  [interp dosage]
  (let [fields (:fields interp)]
    {:has-location (dosage-subject-id interp)
     :type "GENO:0000963" 
     :has-count dosage}))

(def evidence-levels {"3" "SEPIO:0002006"
                      "2" "SEPIO:0002009"
                      "1" "SEPIO:0002007"
                      "0" "SEPIO:0002008"
                      ;; assume moderate evidence for dosage sensitivity unlikely
                      "40: Dosage sensitivity unlikely" "SEPIO:0002009"})

(defn- get-dosage-dependent-fields [interp dosage fields]
  (reduce #(let [ks (cons :fields (first %2))]
               (if-let [v (get-in interp ks)] 
                 (assoc %1 (second %2) v)
                 %1)) {} (get fields dosage)))

(def proposition-fields 
  {1 [[[:customfield-10200] :has-object]]
   3 [[[:customfield-10201] :has-object]]})

(defn- format-comma-separated-list [prefix list-string]
  (if (s/includes? list-string ",")
    (mapv #(str prefix (re-find #"\d+"%)) (-> list-string (s/split #",")))
    (str prefix (re-find #"\d+" list-string))))

(defn- substitute-genetic-condition
  [result]
  (if-let [omim-id (:has-object result)]
    (assoc result :has-object (format-comma-separated-list "OMIM:" omim-id))
    (assoc result :has-object (str "MONDO:0000001"))))

;; ;; as above, 1: loss, 3: gain
(def dosage-assertion-fields
  {1 [[[:customfield-10165 :value] :has-object]
      [[:customfield-10198] :description]]
   3 [[[:customfield-10166 :value] :has-object]
      [[:customfield-10199] :description]]})

(defn- proposition-predicate [interp dosage]
  (let [fields (get-dosage-dependent-fields interp dosage dosage-assertion-fields)]
    (if (= "40: Dosage sensitivity unlikely" (:has-object fields))
      "GENO:0000843"
      "GENO:0000840")))

(defn- construct-proposition
  "Return proposition object from interpretation"
  [interp dosage]
  (let [result {:id (str cg-prefix (:key interp) "x" dosage)
                 :has-subject (construct-gene-dosage-variant interp dosage)
                 :has-predicate (proposition-predicate interp dosage)
                 :type "SEPIO:0002003"}]
    (-> result 
        (merge (get-dosage-dependent-fields interp dosage proposition-fields))       
        substitute-genetic-condition)))

(defn- resolution-date [interp]
  (when-let [resolution-date (get-in interp [:fields :resolutiondate])]
    (time-str-offset-to-instant resolution-date)))

(defn- updated-date [interp]
  (when-let [updated (get-in interp [:fields :updated])]
    (time-str-offset-to-instant updated)))

(defn- construct-contribution
  [interp]
  {:activity-date (resolution-date interp)
   :realizes "SEPIO:0000331"})

(defn- get-evidence [interp dosage]
  (seq (construct-study-findings interp dosage)))

(defn- -add-evidence [result interp dosage]
  (if-let [e (get-evidence interp dosage)]
    (assoc result :has-evidence-with-item e)
    result))

(defn- get-dosage-assertion-fields [interp dosage]
  (let [fields (get-dosage-dependent-fields interp
                                             dosage
                                             dosage-assertion-fields)]
    (if-let [descriptor (evidence-levels (:has-object fields))]
      (assoc fields :has-object (s/trim descriptor))
      (dissoc fields :has-object))))


;; (defn- common-assertion-fields
;;   [interp dosage]
;;   (let [date-part (updated-date interp)
;;         result {:id (str cg-prefix (:key interp) "x" dosage "-" date-part)
;;                 :qualified-contribution (construct-contribution interp)
;;                 :has-subject (construct-proposition interp dosage)
;;                 :is-specified-by "SEPIO:0002004"}
;;         dosage-fields (get-dosage-assertion-fields interp dosage)]
;;     (-> result
;;         (-add-evidence interp dosage)
;;         (merge dosage-fields))))

(defn- construct-scope-assertion
  [interp dosage]
  (merge (common-assertion-fields interp dosage)
         {:has-predicate "SEPIO:0002505"
          :has-object "SEPIO:0002502"
          :type "SEPIO:0002014"}))

(defn- construct-evidence-strength-assertion
  [interp dosage]
  (merge (common-assertion-fields interp dosage)
         {:has-predicate "SEPIO:0000146"
          :type "SEPIO:0002001"}))

(defn- construct-assertion
  "If the assertion is for haploinsufficiency and that the gene is associated with an autosoma
  recessive phenotype, "
  [interp dosage]
  (if (and (= 1 dosage)
           (= "30: Gene associated with autosomal recessive phenotype" 
              (get-in interp [:fields :customfield-10165 :value])))
    (construct-scope-assertion interp dosage)
    (construct-evidence-strength-assertion interp dosage)))

(defn- convert-gene-interp
  "Convert gene interpretation to SEPIO format. Return only records that have a valid
  object associated with the assertion."
  [interp]
  (let [assertions (filterv :has-object [(construct-assertion interp 1)
                                         (construct-assertion interp 3)])
        base-iri (str cg-prefix (:key interp))]
    {:type "SEPIO:0002015"
     :id (str base-iri "-" (updated-date interp))
     :is-version-of base-iri
     :qualified-contribution (construct-contribution interp)
     :is-about (dosage-subject interp)
     :has-part assertions}))

;---------

(defn- gene-iri [curation]
  (get-in curation [:fields :customfield-10157]))

(defn- region-iri [curation]
  (str region-prefix (:key curation)))

(defn- subject-iri [curation]
  (if-let [gene (gene-iri curation)]
    gene
    (region-iri curation)))

(defn- sequence-location [curation build]
  (when-let [loc-str (get-in curation [:fields (build-location build)])]
    (let [[_ chr start-coord end-coord] (re-find #"(\w+):(.+)-(.+)$" loc-str)
          iri (l/blank-node)
          interval-iri (l/blank-node)]
      [iri [[iri :rdf/type :geno/SequenceFeatureLocation]
            [iri :geno/has-reference-sequence (-> chr-to-ref
                                                  (get build)
                                                  (get chr))]
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
  (str cg-prefix "contribution-" (:key curation)  "-" (updated-date curation)))

(defn- contribution
  [iri curation]
  [[iri :sepio/activity-date (resolution-date curation)]
   [iri :bfo/realizes :sepio/InterpreterRole]])

(defn- assertion-iri [curation dosage]
  (str cg-prefix (:key curation) "x" dosage "-" (updated-date curation)))

(defn- proposition-iri [curation dosage]
  (str cg-prefix (:key curation) "x" dosage))

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
                [
                 [assertion-iri :sepio/has-evidence-line-with-item finding-iri]
                 [finding-iri :rdf/type :sepio/StudyFinding]
                 [finding-iri :dc/source (str "PMID:" (re-find #"\d+" pmid))]
                 [finding-iri :dc/description description]
                 ]))
            findings)))

(defn- construct-proposition
  "Return proposition object from interpretation"
  [interp dosage]
  (let [result {:id (str cg-prefix (:key interp) "x" dosage)
                 :has-subject (construct-gene-dosage-variant interp dosage)
                 :has-predicate (proposition-predicate interp dosage)
                 :type "SEPIO:0002003"}]
    (-> result 
        (merge (get-dosage-dependent-fields interp dosage proposition-fields))       
        substitute-genetic-condition)))

(defn- proposition [curation dosage]
  (let [iri (proposition-iri curation dosage)])
  (concat [[iri :sepio/has-subject]]))

(defn- common-assertion-fields
  [iri curation dosage]
  (concat [[iri :sepio/is-specified-by :sepio/DosageSensitivityEvaluationGuideline]
           [iri :sepio/qualified-contribution (contribution-iri curation)]
           [iri :sepio/has-subject (proposition-iri curation dosage)]]
          (case dosage
            1 [[iri :sepio/has-object (get-in curation [:fields :customfield-10165 :value])]
               [iri :dc/description (or (get-in curation [:fields :customfield-10198]) "")]]
            3 [[iri :sepio/has-object (get-in curation [:fields :customfield-10166 :value])]
               [iri :dc/description (or (get-in curation [:fields :customfield-10199]) "")]])
          (study-findings iri curation dosage)
          ;; add proposition (:sepio/has-subject)
          ))

(defn- evidence-strength-assertion [curation dosage]
  (let [iri (assertion-iri curation dosage)]
    (concat (common-assertion-fields iri curation dosage)
     [[iri :rdf/type :sepio/EvidenceLevelAssertion]
      [iri :sepio/has-predicate :sepio/HasEvidenceLevel]
      ])))

(defn- assertion [curation dosage]
  (if (and (= 1 dosage)
           (= "30: Gene associated with autosomal recessive phenotype" 
              (get-in curation [:fields :customfield-10165 :value])))
    ;;    (construct-scope-assertion interp dosage)
    []
    (evidence-strength-assertion curation dosage)))

(defn gene-dosage-report
  [curation]
  (let [base-iri (str cg-prefix (:key curation))
        report-iri (str base-iri "-" (updated-date curation))
        contribution-iri (contribution-iri curation)
        assertions (filterv :has-object [(assertion curation 1)
                                         (assertion curation 3)])]
    (concat [[report-iri :rdf/type :sepio/GeneDosageReport]
             [report-iri :dc/is-version-of base-iri]
             [report-iri :sepio/qualified-contribution contribution-iri]]
            (contribution contribution-iri curation)
            (assertion curation 1)
            ;; (assertion curation 3)
            (topic report-iri curation)
            )))

(defn interpretation-to-sepio [jira-issue]
  ;; (clojure.pprint/pprint   (-> jira-issue
  ;;                              (json/parse-string ->kebab-case-keyword)
  ;;                              gene-dosage-report))
  (-> jira-issue
      (json/parse-string ->kebab-case-keyword)
      gene-dosage-report))









