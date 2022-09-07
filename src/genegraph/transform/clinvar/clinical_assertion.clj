(ns genegraph.transform.clinvar.clinical-assertion
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [genegraph.database.load :as l]
            [genegraph.database.names :refer [local-class-names
                                              local-property-names prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [genegraph.transform.clinvar.util :refer [in?]]
            [io.pedestal.log :as log])
  (:import (genegraph.database.query.types RDFResource)))

(declare statement-context)

(def all-clinsigs ["Affects"
                   "association"
                   "association not found"
                   "Benign"
                   "Benign/Likely benign"
                   "conflicting data from submitters"
                   "confers sensitivity"
                   "drug response"
                   "Established risk allele"
                   "Likely benign"
                   "Likely pathogenic"
                   "Likely pathogenic, low penetrance"
                   "Likely risk allele"
                   "not provided"
                   "other"
                   "Pathogenic"
                   "Pathogenic, low penetrance"
                   "Pathogenic/Likely pathogenic"
                   "protective"
                   "risk factor"
                   "Uncertain risk allele"
                   "Uncertain significance"])


(def clinsig->germline-statement-attribute
  {:direction {"likely benign" "refutes"
               "benign" "refutes"

               "uncertain significance" "uncertain"
               "likely pathogenic" "supports"
               "pathologic" "supports"
               "pathogenic" "supports"
               "non-pathogenic" "refutes"
               "no known pathogenicity" "refutes"
               "variant of unknown significance" "uncertain"}

               ;; Revisit what risk factor means
               ;; "risk factor" "supports"

               ;; not provided likely = nil, but keeping these
               ;; around until that can be ascertained.
               ;; "not provided" "not provided"
               ;; "drug response" "not provided"
               ;; "other" "not provided"
               ;; "protective" "not provided"
               ;; "association" "not provided"

   ;; strength not currently being used, consider removing =tristan
   :strength {"likely benign" "likely"
              "benign" "strong"

              "uncertain significance" "uncertain"
              "likely pathogenic" "likely"
              "pathologic" "strong"
              "pathogenic" "strong"

              "non-pathogenic" "strong"
              "no known pathogenicity" "strong"
              "variant of unknown significance" "uncertain"}

              ;; Per above, need to understand what risk factor means
              ;; "risk factor"

              ;; not providing values for non-germline assertions
              ;; "association"
              ;; "protective"
              ;; "other"
              ;; "not provided"
              ;; "drug response"

   :classification {"likely benign" "loinc:LA26334-5"
                    "benign" "loinc:LA6675-8"
                    "uncertain significance" "loinc:LA26333-7"
                    "variant of unknown significance" "loinc:LA26333-7"
                    "likely pathogenic" "loinc:LA26332-9"
                    "pathologic" "loinc:LA6668-3"
                    "pathogenic" "loinc:LA6668-3"
                    "non-pathogenic" "loinc:LA6675-8"
                    "no known pathogenicity" "loinc:LA6675-8"}})


#_(def domain-root "clingen:")
#_(def clinvar-variation-root "clinvar-variation:")
#_(defn get-from-store [db type id]
    (document-store/get-document db (str type "_" id)))
#_(defn clinsig-term->enum-value [term field]
    (get-in clinsig->germline-statement-attribute [field (s/lower-case term)]))

(defn clinsig->direction
  "Maps a clinsig (normalized form of interpretation_description) to
   direction enumerated values supports, opposes, uncertain"
  [clinsig]
  (comment "We are fixed on 'direction' and the options for values are
            'supports', 'opposes', 'uncertain'.  'supports' for P/LP/ERA/LRA,
            'opposes' for B/LB and 'uncertain' for 'VUS/URA'.
            Always use 'supports' for the ClinVarXXXStatements.")
  (let [mappings {"Benign" "opposes"
                  "Benign/Likely benign" "opposes"
                  "Likely benign" "opposes"
                  "Likely pathogenic" "supports"
                  "Likely pathogenic, low penetrance" "supports"
                  "Pathogenic" "supports"
                  "Pathogenic, low penetrance" "supports"
                  "Pathogenic/Likely pathogenic" "supports"
                  "Uncertain significance" "uncertain"}]
    (get mappings clinsig "uncertain")))

(defn normalize-clinsig-term
  "Returns the mapped normalized term for the raw input term.
   If the term is not known, return the mapping for 'other'"
  [term]
  (get common/normalize-clinsig-map (s/lower-case term)
       (get common/normalize-clinsig-map "other")))

(defn normalize-clinsig-code
  "Returns the mapped normalized code for the raw input term.
   If the term is not known, return the mapping for 'other'"
  [term]
  (get common/normalize-clinsig-codes-map (s/lower-case term)
       (get common/normalize-clinsig-codes-map "other")))

(defn get-clinsig-class
  "Returns the class of a normalized clinsig term
   e.g. path, oth, rf"
  [clinsig]
  (get common/clinsig-class-map clinsig
       (get common/clinsig-class-map "Other")))

(defn classification [assertion]
  (let [clinsig (-> assertion :interpretation_description normalize-clinsig-term)
        qualified (str (get prefix-ns-map "cgterms")
                       (-> assertion
                           :interpretation_description
                           normalize-clinsig-code))]
    {:id qualified
     :type "Coding"
     :label (-> (normalize-clinsig-term (:interpretation_description assertion))
                (s/replace " " "_")
                (s/replace "/" "_"))}))

(defn add-contextualized [event]
  (let [data (:genegraph.annotate/data event)]
    (assoc event
           :genegraph.annotate/data-contextualized
           (merge data statement-context))))

(defn add-data-for-trait [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        trait (:content message)
        unversioned (str (ns-cg "trait") "_" (:id trait))
        fallback-id (str (ns-cg "trait") "_" (:id trait) "." (:release_date message))]
    (-> event
        (assoc
         :genegraph.annotate/data
         {:id fallback-id
          #_(cond
              (:medgen_id trait) (str "medgen:" (:medgen_id trait))
              :else (do (log/warn :fn :add-data-for-trait
                                  :msg "Trait with no medgen id"
                                  :message message)
                        fallback-id))
          :type (case (:type trait)
                  "Disease" "Disease"
                  "Phenotype")
          :medgen_id (:medgen_id trait)
          :clinvar_trait_id (:id trait)
          :release_date (:release_date message)
          :record_metadata {:type "RecordMetadata"
                            :version (:release_date message)
                            :is_version_of unversioned}})
        (assoc :genegraph.annotate/iri fallback-id)
        add-contextualized)))


(defn get-trait-by-id [trait-id release-date]
  (let [;; Most recent trait with trait-id no later than release-date
        rs (q/select "select ?i where {
                      { ?i a :vrs/Disease } union { ?i a :vrs/Phenotype }
                      ?i :cg/clinvar-trait-id ?trait_id .
                      ?i :cg/release-date ?release_date .
                      FILTER(?release_date <= ?max_release_date)
                      }
                      order by desc(?release_date)
                      limit 1"
                     {:trait_id trait-id
                      :max_release_date release-date})]
    (when (= 0 (count rs))
      (log/error :fn :get-trait-by-id
                 :msg "No matching trait found"
                 :trait-id trait-id
                 :release-date release-date))
    (first rs)))

(defn trait-id-to-medgen-id [trait-iri]
  (log/info :fn :trait-id-to-medgen-id :trait-iri trait-iri)
  (or (str "medgen:" (q/ld1-> (q/resource trait-iri) [:cg/medgen-id]))
      trait-iri))

(defn trait-resource-for-output
  "Takes a trait RDFResource and returns it in a GA4GH standard map
   used for outputting to external systems."
  [trait-resource]
  (log/info :fn :trait-resource-for-output :trait trait-resource)
  (when trait-resource
    {:id (trait-id-to-medgen-id (str trait-resource))
     :type (str (q/ld1-> trait-resource [:rdf/type]))}))

(defn compact-one-element-condition
  "If the condition has a members array with only one element, return that element.
   (copies over :clinvar_trait_set_id)"
  [condition]
  (if (= 1 (count (:members condition)))
    (-> condition :members first
        (merge (select-keys condition [:clinvar_trait_set_id])))
    condition))

(defn add-data-for-trait-set [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        trait-set (:content message)
        data (->
              {:id (str (ns-cg "trait_set_") (:id trait-set)
                        "." (:release_date message))
               :type "Condition"
               :clinvar_trait_set_id (:id trait-set)
               :release_date (:release_date message)
               :record_metadata {:type "RecordMetadata"
                                 :is_version_of (str (ns-cg "trait_set_") (:id trait-set))
                                 :version (:release_date message)}
               :members (let [trait-ids (:trait_ids trait-set)]
                          (log/debug :fn :add-data-for-trait-set
                                     :trait-ids trait-ids)
                          ;; Unversioned identifiers for the traits
                          (map #(str (ns-cg "trait") "_" %)
                               trait-ids)
                          #_(->> trait-ids
                                 (map #(get-trait-by-id % (:release_date message)))
                                 ((fn [traits]
                                    (when (some #(= nil %) traits)
                                      (log/error :msg "Got nil traits"
                                                 :trait-set trait-set
                                                 :trait-ids trait-ids
                                                 :traits traits
                                                 :release-date (:release_date message)))
                                    traits))
                                 (map #(trait-resource-for-output %))))})]
    (-> (assoc
         event
         :genegraph.annotate/data
         data
         :genegraph.annotate/iri
         (str (ns-cg "trait_set_") (:id trait-set) "." (:release_date message)))
        add-contextualized)))

(defn get-trait-resource-by-version-of
  [trait-vof max-release-date]
  (log/info :fn :get-trait-resource-by-version-of
            :trait-vof trait-vof :max-release-date max-release-date)
  (let [rs (q/select "select ?i where {
                      { ?i a :vrs/Disease }
                      union { ?i a :vrs/Phenotype }
                      ?i :vrs/record-metadata ?rmd .
                      ?rmd :dc/is-version-of ?vof .
                      ?rmd :owl/version-info ?release_date .
                      FILTER(?release_date <= ?max_release_date)
                      }
                      order by desc(?release_date)
                      limit 1"
                     {:vof (q/resource trait-vof)
                      :max_release_date max-release-date})]
    (if (= 0 (count rs))
      (log/error :msg "No trait found with version-of"
                 :is-version-of trait-vof)
      (first rs))))

(defn trait-set-resource-for-output
  "Takes an RDFResource for a normalized trait-set object (:vrs/Condition).
   Uses :vrs/members relationship to get the traits in it."
  [trait-set]
  (let [trait-set-type (q/ld1-> trait-set [:rdf/type])]
    (log/info :trait-set-type trait-set-type)
    (case (s/replace (str trait-set-type) (get prefix-ns-map "vrs") "")
      "Disease" {:id (trait-id-to-medgen-id (str trait-set))
                 :type (str trait-set-type)}
      "Phenotype" {:id (trait-id-to-medgen-id (str trait-set))
                   :type (str trait-set-type)}
      "Condition" (-> {:id (str trait-set)
                       :type "Condition"
                       :members (->> (q/ld-> trait-set [:vrs/members])
                                     (map #(get-trait-resource-by-version-of
                                            %
                                            (q/ld1-> trait-set [:vrs/record-metadata
                                                                :owl/version-info])))
                                     (map #(trait-resource-for-output %)))}
                      compact-one-element-condition)
      (do (log/error :fn :trait-set-resource-for-output :msg "Unknown type"
                     :trait-set-type trait-set-type)
          {:id (str trait-set)
           :type (str trait-set-type)}))))

(defn get-trait-set-by-id [trait-set-id release-date]
  (log/info :fn :get-trait-set-by-id
            :trait-set-id trait-set-id
            :release-date release-date)
  (let [rs (q/select "select ?i where {
                      { ?i a :vrs/Condition }
                      union { ?i a :vrs/Disease }
                      union { ?i a :vrs/Phenotype }
                      ?i :vrs/record-metadata ?rmd .
                      ?rmd :owl/version-info ?release_date .
                      ?i :cg/clinvar-trait-set-id ?trait_set_id .
                      FILTER(?release_date <= ?max_release_date) }
                      order by desc(?release_date)
                      limit 1"
                     {:trait_set_id trait-set-id
                      :max_release_date release-date})]
    (when (= 0 (count rs))
      (log/error :fn :get-trait-by-id
                 :msg "No matching trait set"
                 :trait-set-id trait-set-id
                 :release-date release-date))
    (first rs)))

(defn get-trait-set-by-version-of
  [^RDFResource trait-set-vof ^String release-date]
  (log/info :fn :get-trait-set-by-id
            :trait-set-vof trait-set-vof
            :release-date release-date)
  (let [rs (q/select "select ?i where {
                      { ?i a :vrs/Condition }
                      union { ?i a :vrs/Disease }
                      union { ?i a :vrs/Phenotype }
                      ?i :vrs/record-metadata ?rmd .
                      ?rmd :dc/is-version-of ?vof .
                      ?rmd :owl/version-info ?release_date .
                      FILTER(?release_date <= ?max_release_date) }
                      order by desc(?release_date)
                      limit 1"
                     {:vof (q/resource trait-set-vof)
                      :max_release_date release-date})]
    (when (= 0 (count rs))
      (log/error :fn :get-trait-set-by-version-of
                 :msg "No matching trait set"
                 :trait-set-vof trait-set-vof
                 :release-date release-date))
    (first rs)))

(defn ^RDFResource proposition-object [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        trait-set-id (:trait_set_id assertion)]
    (if (not (nil? trait-set-id))
      (let [trait-set-resource (get-trait-set-by-id trait-set-id (:release_date message))]
        trait-set-resource)
      (log/warn :fn :proposition-object :msg :nil-trait-set-id :message message))))

(defn variation-descriptor-by-clinvar-id [clinvar-id release-date]
  (log/info :fn :variation-descriptor-by-clinvar-id
            :clinvar-id clinvar-id
            :release-date release-date)
  (let [qualified-id (str iri/clinvar-variation clinvar-id)
        rs (q/select "select ?i where {
                      ?i a :vrs/CanonicalVariationDescriptor .
                      ?i :vrs/extensions ?ext .
                      ?ext :vrs/name \"clinvar_variation\" .
                      ?ext :rdf/value ?variation_id .
                      ?i :vrs/record-metadata ?rmd .
                      ?rmd :owl/version-info ?version .
                      FILTER(?version <= ?max_release_date) }"
                     {:variation_id qualified-id
                      :max_release_date release-date})]
    (when (and (< 1 (count rs))
               (< 1 (count (set (map :id rs)))))
      (log/error :fn :get-trait-byid
                 :msg "Multiple matching trait sets with different identifiers"
                 :clinvar-id clinvar-id
                 :release-date release-date
                 :rs (map str rs)))
    (first rs)))

(defn ^String statement-type
  "Returns a Statement sub-class type string for a raw interpretation-description."
  [interpretation-description]
  (let [clinsig (normalize-clinsig-term interpretation-description)
        clinsig-class (get-clinsig-class clinsig)
        group-map {"path" "VariationGermlinePathogenicityStatement"
                   "dr" "ClinVarDrugResponseStatement"
                   "oth" "ClinVarOtherStatement"}]
    (if (contains? group-map clinsig-class)
      (get group-map clinsig-class)
      (get group-map "oth"))))

(def statement-type-to-proposition-type
  {"VariationGermlinePathogenicityStatement" "VariationGermlinePathogenicityProposition"
   "ClinVarDrugResponseStatement" "ClinVarDrugResponseProposition"
   "ClinVarOtherStatement" "ClinVarOtherProposition"})

(defn clinsig-and-statement-type-to-predicate
  [clinsig stmt-type]
  ;; TODO add the predicates to the clinvar_clinsig_normalized.csv
  (let [path-causal-clinsigs ["Benign"
                              "Benign/Likely benign"
                              "Likely benign"
                              "Likely pathogenic"
                              "Likely pathogenic, low penetrance"
                              "Pathogenic"
                              "Pathogenic, low penetrance"
                              "Pathogenic/Likely pathogenic"
                              "Uncertain significance"]
        path-risk-clinsigs ["Established risk allele"
                            "Likely risk allele"
                            "Uncertain risk allele"]]
    (case stmt-type
      "VariationGermlinePathogenicityStatement"
      (cond (in? clinsig path-causal-clinsigs) "causes_mendelian_condition"
            (in? clinsig path-risk-clinsigs) "increases_risk_for_condition"
            :else (do (log/error :fn :clinsig-and-statement-type-to-predicate
                                 :msg "Could not interpret clinsig"
                                 :clinsig clinsig :stmt-type stmt-type)
                      nil))
      "ClinVarDrugResponseStatement" "has_clinvar_drug_response"
      "ClinVarOtherStatement" "has_clinvar_other"
      (log/error :fn :clinsig-and-statement-type-to-predicate
                 :msg "Unknown statement type"
                 :stmt-type stmt-type
                 :clinsig clinsig))))

(defn proposition [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        #_#_subject-descriptor (variation-descriptor-by-clinvar-id
                                (get assertion :variation_id)
                                (get message :release_date))
        stmt-type (statement-type (:interpretation_description assertion))]
    {:type (get statement-type-to-proposition-type stmt-type)
     :subject (get assertion :variation_id)
     #_(if (not (nil? subject-descriptor))
         (let [subject (q/ld1-> subject-descriptor [:rdf/value])]
           (str subject))
         (do (log/error :fn :proposition
                        :msg "No matching subject descriptor found"
                        :variation-id (get assertion :variation_id)
                        :release-date (get message :release_date)
                        :message message)
             nil))
     :predicate (clinsig-and-statement-type-to-predicate
                 (normalize-clinsig-term (:interpretation_description assertion))
                 stmt-type)
     :object (str (ns-cg "trait_set_") (:trait_set_id assertion))
     #_(str (:trait_set_id assertion))
     #_(let [;; Condition, Disease, Phenotype
             proposition-object-resource (proposition-object event)]
         (if proposition-object-resource
           (trait-set-resource-for-output proposition-object-resource)
                 ;; Not Provided (or some error in upstream trait mapping)
           {:id (str (ns-cg "not_provided"))
            :type "Phenotype"}))}))


(defn method
  ;; TODO handle pubmed method citations
  ;; "AttributeSet": {
  ;;       "Attribute": {
  ;;           "$": "Pharmacogenomics knowledge for personalized medicine",
  ;;           "@Type": "AssertionMethod"
  ;;       },
  ;;       "Citation": {
  ;;           "ID": {
  ;;               "$": "22992668",
  ;;               "@Source": "PubMed"
  ;;           }
  ;;       }
  ;;   }
  "Returns a Variant Annotation : Method object for a clinical_assertion"
  [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        has-method? (= "AssertionMethod"
                       (get-in assertion [:content "AttributeSet" "Attribute" "@Type"]))
        method-label (get-in assertion [:content "AttributeSet" "Attribute" "$"])
        method-url (get-in assertion [:content "AttributeSet" "Citation" "URL" "$"])
        method-pubmed (let [citation (get-in assertion [:content "AttributeSet" "Citation"])]
                        (when (= "PubMed" (get-in citation ["ID" "@Source"]))
                          (str "PMID:" (get-in citation ["ID" "$"]))))
        #_(str iri/clinvar-assertion (:id assertion) "." (:release_date message) "_Method")]
    (when has-method?
      {:id (str "_:" (l/blank-node))
       :type "Method"
       :label method-label
       :is_reported_in {;; TODO use the method URL as the id if it exists?
                                ;; Unreliable as not all assertions have one
                                ;; title
                                ;; extensions
                                ;; xrefs
                        :id (or method-url method-pubmed (str "_:" (l/blank-node)))
                        :type "Document"}})))

(defn description
  "Takes :interpretation_comments from the assertion.
   Adds line breaks between them."
  [event]
  (when-let [interpretation-comments
             (get-in event
                     [:genegraph.transform.clinvar.core/parsed-value
                      :content
                      :interpretation_comments])]
    (s/join "\n" (->> interpretation-comments
                      (map #(json/parse-string % true))
                      (map :text)))))

(defn contributions [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        qualified-submitter (str iri/submitter (:submitter_id assertion))]
    (cond-> []
      (:interpretation_date_last_evaluated assertion)
      (conj
       ;; Approver (maybe an evaluator role?)
       {:type "Contribution"
        :agent qualified-submitter
        :date (:interpretation_date_last_evaluated assertion)
        :role "Approver"})
      (:date_last_updated assertion)
      (conj
       ;; Submitter
       {:type "Contribution"
        :agent qualified-submitter
        :date (:date_last_updated assertion)
        :role "Submitter"}))))


(defn contribution-resource-for-output
  "Takes an RDFResource for a contribution,
   and returns a map for a GA4GH Contribution"
  [contribution-resource]
  (when contribution-resource
    {:type (q/ld1-> contribution-resource [:rdf/type])
     :agent (q/ld1-> contribution-resource [:vrs/agent])
     :date (q/ld1-> contribution-resource [:vrs/date])
     :role (q/ld1-> contribution-resource [:vrs/role])}))

(defn classification-resource-for-output
  "Takes an RDFResource for a classification,
   and returns a map for a GA4GH Classification"
  [classification-resource]
  (when classification-resource
    {:type (q/ld1-> classification-resource [:rdf/type])
     :id (str classification-resource)
     :label (q/ld1-> classification-resource [:rdfs/label])}))

(defn record-metadata-resource-for-output
  [record-metadata-resource]
  (when record-metadata-resource
    {:type (q/ld1-> record-metadata-resource [:rdf/type])
     :is_version_of (q/ld1-> record-metadata-resource [:dc/is-version-of])
     :version (q/ld1-> record-metadata-resource [:owl/version-info])}))

#_(defn condition-resource-for-output
  ;; TODO compact single-member Condition to the member
    [condition-resource]

    (when condition-resource
      (let [condition
            {:id (str condition-resource)
             :type (q/ld1-> condition-resource [:rdf/type])
             :record_metadata (record-metadata-resource-for-output
                               (q/ld1-> condition-resource [:vrs/record-metadata]))
             :members (map trait-resource-for-output
                           (q/ld-> condition-resource [:vrs/members]))}]
        (if (= 1 (count (:members condition)))
          (-> condition :members first)))))

(defn proposition-resource-for-output
  "Takes an RDFResource for a proposition and its subject,
   and returns a map for a GA4GH Proposition"
  [proposition-resource subject-resource release-date]
  (log/info :fn :proposition-resource-for-output
            :proposition-resource proposition-resource
            :subject-resource subject-resource
            :release-date release-date)
  (when proposition-resource
    {:type (q/ld1-> proposition-resource [:rdf/type])
     :subject (str subject-resource)
     :predicate (q/ld1-> proposition-resource [:vrs/predicate])
     :object (let [object-vof (q/ld1-> proposition-resource [:vrs/object])]
               (log/info :fn :proposition-resource-for-output
                         :object-vof object-vof)
               ;; with the is_version_of of the object (a trait-set), get latest
               (trait-set-resource-for-output
                (get-trait-set-by-version-of object-vof release-date)))}))

(defn clinical-assertion-resource-for-output
  ;; TODO remove keys with nil values
  ;; TODO verify method is working correctly (seems to be, some are just not provided)
  ;; TODO add type RecordMetadata to record_metadata
  ;; TODO when interpretation_date_last_evaluated is null, what to do for Approver contribution?
  ;;      leave it out with just the submitter contribution?
  ;;      replace it with another contribution activity? This would be inconsistent
  ;;      unless that activity was also added to all records. Default: 3 contributions
  ;;      TODO addendum: all drug response stmts from 2019-07-01 do not have interpretation_date_last_evaluated
  ;; TODO handle :event_type delete for all these records.
  "Takes an RDFResource for a clinical assertion Statement and
   puts data it references into a GA4GH Statement structure for output."
  [assertion-resource]
  (log/info :fn :clinical-assertion-resource-for-output :assertion-resource assertion-resource)
  (let [release-date (q/ld1-> assertion-resource [:vrs/record-metadata
                                                  :owl/version-info])
        ;; Assertion is stored with the subject-descriptor just being the variation id
        subject-descriptor (variation-descriptor-by-clinvar-id
                            (q/ld1-> assertion-resource [:vrs/subject-descriptor])
                            release-date)]
    {:id (str assertion-resource)
     :type (q/ld1-> assertion-resource [:rdf/type])
     :label (q/ld1-> assertion-resource [:rdfs/label])
     :description (q/ld1-> assertion-resource [:vrs/description])
     :method (q/ld1-> assertion-resource [:vrs/method])
     :contributions (->> (q/ld-> assertion-resource [:vrs/contributions])
                         (map contribution-resource-for-output))
     :record_metadata (-> (q/ld1-> assertion-resource [:vrs/record-metadata])
                          record-metadata-resource-for-output)
     :direction (q/ld1-> assertion-resource [:vrs/direction])
     :subject_descriptor (str subject-descriptor)
   ;;:object_descriptor nil ; do we need this ? list of disease names, per Larry (xrefs?)
     :classification (-> assertion-resource
                         (q/ld1-> [:vrs/classification])
                         classification-resource-for-output)
     :target_proposition (-> assertion-resource
                             (q/ld1-> [:vrs/target-proposition])
                             (proposition-resource-for-output
                              (q/ld1-> subject-descriptor [:rdf/value])
                              release-date))}))

(defn add-data-for-clinical-assertion [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        vof (str (ns-cg "SCV_Statement_") (:id assertion))
        id (str vof "." (:release_date message))
        stmt-type (statement-type (:interpretation_description assertion))
        _ (log/info :stmt-type stmt-type)]
    (-> (assoc
         event
         :genegraph.annotate/data
         {:id id
          :type stmt-type
          :label (:title assertion)
          ;; TODO
          ;;:extensions nil
          :description (description event)
          :method (method event)
          :contributions (contributions event)
          ;;:is_reported_in nil ; ClinVar?
          :record_metadata {:type "RecordMetadata"
                            :is_version_of vof
                            :version (:release_date message)}
          :direction (-> (:interpretation_description assertion)
                         normalize-clinsig-term
                         clinsig->direction)
          :subject_descriptor (get assertion :variation_id)
          #_#_:subject_descriptor (str (variation-descriptor-by-clinvar-id
                                        (get assertion :variation_id)
                                        (get message :release_date)))
          ;;:object_descriptor nil ; do we need this ? list of disease names, per Larry (xrefs?)
          :classification (classification assertion)
          :target_proposition (proposition event)}
         :genegraph.annotate/iri
         (str (ns-cg "clinical_assertion_") (:id assertion) "." (:release_date message)))
        add-contextualized)))


(def statement-context
  {"@context"
   {; Properties
    ;"object" {"@id" (str (get local-property-names :sepio/has-object))
    ;          "@type" "@id"}
    "is_version_of" {"@id" (str (get local-property-names :dc/is-version-of))
                     "@type" "@id"}
    "type" {"@id" "@type"
            "@type" "@id"}
    ;"release_date" {"@id" (str (get local-property-names :cg/release-date))}
    "name" {"@id" (str (get local-property-names :vrs/name))}
    "record_metadata" {"@id" (str (get local-property-names :vrs/record-metadata))}

    ;"value" {"@id" "@value"}
    "value" {"@id" "rdf:value"}
    "label" {"@id" "rdfs:label"}

    "replaces" {"@id" (str (get local-property-names :dc/replaces))
                "@type" "@id"}
    "is_replaced_by" {"@id" (str (get local-property-names :dc/is-replaced-by))
                      "@type" "@id"}
    "version" {"@id" (str (get local-property-names :owl/version-info))}
    "id" {"@id" "@id"
          "@type" "@id"}
    ;; "_id" {"@id" "@id"
    ;;        "@type" "@id"}
    "Extension" {"@id" (str (get local-class-names :vrs/Extension))}
    "CategoricalVariationDescriptor"
    {"@id" (str (get local-class-names :vrs/CategoricalVariationDescriptor))
     "@type" "@id"}
    "CanonicalVariationDescriptor"
    {"@id" (str (get local-class-names :vrs/CanonicalVariationDescriptor))
     "@type" "@id"}

    ; eliminate vrs prefixes on vrs variation terms
    ; VRS properties
    "variation" {"@id" (str (get prefix-ns-map "vrs") "variation")}
    "complement" {"@id" (str (get prefix-ns-map "vrs") "complement")}
    "interval" {"@id" (str (get prefix-ns-map "vrs") "interval")}
    "start" {"@id" (str (get prefix-ns-map "vrs") "start")}
    "end" {"@id" (str (get prefix-ns-map "vrs") "end")}
    "location" {"@id" (str (get prefix-ns-map "vrs") "location")}
    "state" {"@id" (str (get prefix-ns-map "vrs") "state")}
    "sequence_id" {"@id" (str (get prefix-ns-map "vrs") "sequence_id")}
    "sequence" {"@id" (str (get prefix-ns-map "vrs") "sequence")}
    "agent" {"@id" (str (get prefix-ns-map "vrs") "agent")}
    "date" {"@id" (str (get prefix-ns-map "vrs") "date")}
    "role" {"@id" (str (get prefix-ns-map "vrs") "role")}
    "method" {"@id" (str (get prefix-ns-map "vrs") "method")}
    "contributions" {"@id" (str (get prefix-ns-map "vrs") "contributions")}
    "direction" {"@id" (str (get prefix-ns-map "vrs") "direction")}
    "description" {"@id" (str (get prefix-ns-map "vrs") "description")}
    "target_proposition" {"@id" (str (get prefix-ns-map "vrs") "target_proposition")}
    "subject" {"@id" (str (get prefix-ns-map "vrs") "subject")}
    "predicate" {"@id" (str (get prefix-ns-map "vrs") "predicate")}
    "object" {"@id" (str (get prefix-ns-map "vrs") "object")}
    "subject_descriptor" {"@id" (str (get prefix-ns-map "vrs") "subject_descriptor")}
    "classification" {"@id" (str (get prefix-ns-map "vrs") "classification")}



    ; map plurals to known guaranteed array types
    "members" {"@id" (str (get local-property-names :vrs/members))
               "@container" "@set"}
    "extensions" {"@id" (str (get local-property-names :vrs/extensions))
                  "@container" "@set"}
    "expressions" {"@id" (str (get local-property-names :vrs/expressions))
                   "@container" "@set"}

    ; VRS entities
    "CanonicalVariation" {"@id" (str (get prefix-ns-map "vrs") "CanonicalVariation")
                          "@type" "@id"}
    "Allele" {"@id" (str (get prefix-ns-map "vrs") "Allele")
              "@type" "@id"}
    "SequenceLocation" {"@id" (str (get prefix-ns-map "vrs") "SequenceLocation")
                        "@type" "@id"}
    "SequenceInterval" {"@id" (str (get prefix-ns-map "vrs") "SequenceInterval")
                        "@type" "@id"}
    "Number" {"@id" (str (get prefix-ns-map "vrs") "Number")
              "@type" "@id"}
    "LiteralSequenceExpression" {"@id" (str (get prefix-ns-map "vrs") "LiteralSequenceExpression")
                                 "@type" "@id"}
    "VariationMember" {"@id" (str (get prefix-ns-map "vrs") "VariationMember")
                       "@type" "@id"}
    "Disease" {"@id" (str (get prefix-ns-map "vrs") "Disease")
               "@type" "@id"}
    "Phenotype" {"@id" (str (get prefix-ns-map "vrs") "Phenotype")
                 "@type" "@id"}
    "Condition" {"@id" (str (get prefix-ns-map "vrs") "Condition")
                 "@type" "@id"}
    "Contribution" {"@id" (str (get prefix-ns-map "vrs") "Contribution")
                    "@type" "@id"}
    "Document" {"@id" (str (get prefix-ns-map "vrs") "Document")
                "@type" "@id"}
    "RecordMetadata" {"@id" (str (get prefix-ns-map "vrs") "RecordMetadata")
                      "@type" "@id"}
    "VariationGermlinePathogenicityStatement" {"@id" (str (get prefix-ns-map "vrs") "VariationGermlinePathogenicityStatement")
                                               "@type" "@id"}
    "ClinVarDrugResponseStatement" {"@id" (str (get prefix-ns-map "vrs") "ClinVarDrugResponseStatement")
                                    "@type" "@id"}
    "ClinVarOtherStatement" {"@id" (str (get prefix-ns-map "vrs") "ClinVarOtherStatement")
                             "@type" "@id"}

    ; Prefixes
    ;"https://vrs.ga4gh.org/terms/"
    "rdf" {"@id" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           "@prefix" true}
    "rdfs" {"@id" "http://www.w3.org/2000/01/rdf-schema#"
            "@prefix" true}
    "vrs" {"@id" (get prefix-ns-map "vrs")
           "@prefix" true}
    "cgterms" {"@id" (get prefix-ns-map "cgterms")
               "@prefix" true}
    "@vocab" (get prefix-ns-map "cgterms")}})
