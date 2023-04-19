(ns genegraph.transform.clinvar.clinical-assertion
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [genegraph.database.load :as l]
            [genegraph.database.names
             :refer [local-class-names
                     local-property-names
                     prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.rocksdb :as rocks]
            [genegraph.sink.document-store :as docstore]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [genegraph.transform.clinvar.util
             :refer [in?
                     into-sequential-if-not]]
            [genegraph.transform.clinvar.variation :as variation]
            [io.pedestal.log :as log]
            [mount.core :as mount :refer [defstate]])
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
  {:classification {"likely benign" "loinc:LA26334-5"
                    "benign" "loinc:LA6675-8"
                    "uncertain significance" "loinc:LA26333-7"
                    "variant of unknown significance" "loinc:LA26333-7"
                    "likely pathogenic" "loinc:LA26332-9"
                    "pathologic" "loinc:LA6668-3"
                    "pathogenic" "loinc:LA6668-3"
                    "non-pathogenic" "loinc:LA6675-8"
                    "no known pathogenicity" "loinc:LA6675-8"}})

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
   If the term is not known, return the mapping for 'other'.
   TODO add error messages to return"
  [term]
  (or (some-> term str/lower-case (#(get common/normalize-clinsig-map %)))
      (get common/normalize-clinsig-map "other")))

(defn normalize-clinsig-code
  "Returns the mapped normalized code for the raw input term.
   If the term is not known, return the mapping for 'other'"
  [term]
  (or (some-> term str/lower-case (#(get common/normalize-clinsig-codes-map %)))
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
                (str/replace " " "_")
                (str/replace "/" "_"))}))

(defn add-contextualized [event]
  (let [data (:genegraph.annotate/data event)]
    (assoc event
           :genegraph.annotate/data-contextualized
           (merge data statement-context))))

(defstate trait-data-db
  :start (rocks/open "trait-snapshot.db")
  :stop (rocks/close trait-data-db))

(defn add-data-for-trait [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        trait (:content message)
        unversioned (str (ns-cg "trait") "_" (:id trait))
        tid (str (ns-cg "trait") "_" (:id trait) "." (:release_date message))
        data {:id tid
              :type (case (:type trait)
                      "Disease" "Disease"
                      "Phenotype")
              :medgen_id (:medgen_id trait)
              :clinvar_trait_id (:id trait)
              :release_date (:release_date message)
              :record_metadata (merge {:type "RecordMetadata"
                                       :version (:release_date message)
                                       :is_version_of unversioned}
                                      (when (= "delete" (:event_type message))
                                        {:deleted true}))}]
    (-> event
        (assoc :genegraph.annotate/data-db trait-data-db
               :genegraph.annotate/data-id tid
               :genegraph.annotate/data data
               :genegraph.annotate/iri tid)
        add-contextualized)))

(defn trait-id-to-medgen-id
  "The reason we can keep the fully qualified trait-iri URI as the id and still
   validate against the schema requiring a CURIE is that 'http:' is a valid prefix."
  ;; TODO address this CURIE issue, either by compacting the namespace of the IRI, or
  ;; changing the schema to accept URI types.
  [trait-iri]
  (log/debug :fn :trait-id-to-medgen-id :trait-iri trait-iri)
  (if-let [medgen-id (q/ld1-> (q/resource trait-iri) [:cg/medgen-id])]
    (str "medgen:" medgen-id)
    trait-iri))

(defn trait-resource-for-output
  "Takes a trait RDFResource and returns it in a GA4GH standard map
   used for outputting to external systems."
  [trait-resource]
  (log/debug :fn :trait-resource-for-output :trait trait-resource)
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

(defstate trait-set-data-db
  :start (rocks/open "trait-set-snapshot.db")
  :stop (rocks/close trait-set-data-db))

(defn add-data-for-trait-set [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        trait-set (:content message)
        iri-unversioned (str (ns-cg "trait_set_") (:id trait-set))
        iri (str iri-unversioned "." (:release_date message))
        data {:id iri
              :type "Condition"
              :clinvar_trait_set_id (:id trait-set)
              :release_date (:release_date message)
              :record_metadata (merge {:type "RecordMetadata"
                                       :is_version_of (str (ns-cg "trait_set_") (:id trait-set))
                                       :version (:release_date message)}
                                      (when (= "delete" (:event_type message))
                                        {:deleted true}))
              :members (let [trait-ids (:trait_ids trait-set)]
                         (log/debug :fn :add-data-for-trait-set
                                    :trait-ids trait-ids)
                          ;; Unversioned identifiers for the traits
                         (map #(str (ns-cg "trait") "_" %)
                              trait-ids))}]
    (-> event
        (assoc :genegraph.annotate/data-db trait-set-data-db
               :genegraph.annotate/data-id (:id data)
               :genegraph.annotate/data data
               :genegraph.annotate/iri (:id data))
        add-contextualized)))

(defn get-trait-resource-by-version-of
  [trait-vof max-release-date]
  (log/debug :fn :get-trait-resource-by-version-of
             :trait-vof trait-vof :max-release-date max-release-date)
  (comment
    "See comment in get-trait-set-resource-by-version-of"
    "{ ?i a :vrs/Disease } union { ?i a :vrs/Phenotype }")
  (let [rs (q/select
            (str/join \space
                      ["select ?i where {"
                       "?i :vrs/record-metadata ?rmd ."
                       "?rmd :dc/is-version-of ?vof ."
                       "?rmd :owl/version-info ?release_date ."
                       "FILTER(?release_date <= ?max_release_date) }"
                       "order by desc(?release_date)"
                       "limit 1"])
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
    (log/debug :trait-set-type trait-set-type)
    (case (str/replace (str trait-set-type) (get prefix-ns-map "vrs") "")
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

(defn get-trait-set-by-version-of
  [^RDFResource trait-set-vof ^String release-date]
  (log/debug :fn :get-trait-set-by-version-of
             :trait-set-vof trait-set-vof
             :release-date release-date)
  (comment
    "This resource type pattern was originally included in the following select."
    "It was found to cost hundreds of milliseconds and was taken out. Assuming
     dc/is-version-of is an identifier that can only have one of these types, the
     query will perform successfully without it. If this assumption becomes no longer
     true, it will need to be re-added"
    "{ ?i a :vrs/Condition } union { ?i a :vrs/Disease } union { ?i a :vrs/Phenotype }")
  (let [rs (q/select
            (str/join \space
                      ["select ?i where {"
                       "?i :vrs/record-metadata ?rmd ."
                       "?rmd :dc/is-version-of ?vof ."
                       "?rmd :owl/version-info ?release_date ."
                       "FILTER(?release_date <= ?max_release_date) }"
                       "order by desc(?release_date)"
                       "limit 1"])
            {:vof (q/resource trait-set-vof)
             :max_release_date release-date})]
    (when-not (== 1 (count rs))
      (log/error :fn :get-trait-set-by-version-of
                 :msg "No matching trait set"
                 :trait-set-vof trait-set-vof
                 :release-date release-date))
    (first rs)))

;; TODO This function is slower than it should be. Look into this.
(defn variation-descriptor-by-clinvar-id [clinvar-id release-date]
  (log/debug :fn :variation-descriptor-by-clinvar-id
             :clinvar-id clinvar-id
             :release-date release-date)
  (let [qualified-id (str iri/clinvar-variation clinvar-id)

        _  (comment (q/select (str
                               "select ?i where { \n"
                               "?i a :vrs/CanonicalVariationDescriptor . \n"
                               "?i :vrs/extensions ?ext . \n"
                               "#?ext :vrs/name \"clinvar_variation\" . \n"
                               "?ext :rdf/value ?variation_id . \n"
                               "?i :vrs/record-metadata ?rmd . \n"
                               "?rmd :owl/version-info ?version . \n"
                               "FILTER(?version <= ?max_release_date) } \n"
                               "order by desc(?version) \n"
                               "limit 1")
                              {:variation_id qualified-id
                               :max_release_date release-date}))
        ;; TODO the "clinvar_variation" pattern for ext[:vrs/name] should be
        ;; included, but it is very slow.
        ;;#?ext :vrs/name \"clinvar_variation \" .
        rs (q/select
            (str/join \space
                      ["select ?i where {"
                       "?i a :vrs/CanonicalVariationDescriptor ."
                       "?i :vrs/extensions ?ext ."
                       "?ext :rdf/value ?variation_id ."
                       "?i :vrs/record-metadata ?rmd ."
                       "?rmd :owl/version-info ?version ."
                       "FILTER(?version <= ?max_release_date) }"
                       "order by desc(?version)"
                       "limit 1"])
            {:variation_id qualified-id
             :max_release_date release-date})]
    (when (and (< 1 (count rs))
               (< 1 (count (set (map :id rs)))))
      (log/error :fn :variation-descriptor-by-clinvar-id
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

(defn proposition
  "Returns a Proposition object for a clinical_assertion record.
   Some clinical_assertions do not have trait_set_id values, and in those,
   the :object of the proposition will be omitted."
  [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        stmt-type (statement-type (:interpretation_description assertion))]
    (merge {:type (get statement-type-to-proposition-type stmt-type)
            :subject (get assertion :variation_id)
            :predicate (clinsig-and-statement-type-to-predicate
                        (normalize-clinsig-term (:interpretation_description assertion))
                        stmt-type)}
           (when (:trait_set_id assertion)
             {:object (str (ns-cg "trait_set_") (:trait_set_id assertion))}))))

(defn method
  ;; pubmed method citation format:
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
        assertion-method-obj (-> assertion
                                 (get-in [:content "AttributeSet"])
                                 (into-sequential-if-not)
                                 (->> (filter #(= "AssertionMethod"
                                                  (get-in % ["Attribute" "@Type"])))
                                      first))
        method-label (get-in assertion-method-obj ["Attribute" "$"])
        method-url (get-in assertion-method-obj ["Citation" "URL" "$"])
        method-pubmed (let [citation (get-in assertion-method-obj ["Citation"])]
                        (when (= "PubMed" (get-in citation ["ID" "@Source"]))
                          (str "PMID:" (get-in citation ["ID" "$"]))))]
    (when assertion-method-obj
      {;; If a deterministic id is needed:
       ;; (str iri/clinvar-assertion (:id assertion) "." (:release_date message) "_Method")
       :id (str "_:" (l/blank-node))
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
    (str/join "\n" (->> interpretation-comments
                        (map #(json/parse-string % true))
                        (map :text)))))

(defn contributions [event]
  ;; TODO add Agent type to property-names and context
  ;; TODO add resolution of agent name/label against ClinVar :submitter records
  ;; TODO not sure how the Agents will be stored in Jena, probably will end up with
  ;; a lot of duplicate Agent nodes, in different clinicalassertion named graphs,
  ;; and not be able to distinguish them since they're identical other than the graph name.
  ;; This should be okay since they really are identical for now (just id and type).
  ;; But after we include the submitter name, they may not be, since submitter names can change.
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        qualified-submitter (str iri/submitter (:submitter_id assertion))
        agent {:id qualified-submitter
               :type "Agent"}]
    (cond-> []
      (:interpretation_date_last_evaluated assertion)
      (conj
       ;; Approver (maybe an evaluator role?)
       {:type "Contribution"
        :contributor agent
        :date (:interpretation_date_last_evaluated assertion)
        :activity {:type "Coding"
                   :id (ns-cg "Approver")
                   :label "Approver"}})
      (:date_last_updated assertion)
      (conj
       ;; Submitter
       {:type "Contribution"
        :contributor agent
        :date (:date_last_updated assertion)
        :activity {:type "Coding"
                   :id (ns-cg "Submitter")
                   :label "Submitter"}}))))


(defn contribution-resource-for-output
  "Takes an RDFResource for a contribution,
   and returns a map for a GA4GH Contribution"
  [contribution-resource]
  (when contribution-resource
    {:type (q/ld1-> contribution-resource [:rdf/type])
     :contributor (let [contributor-resource
                        (q/ld1-> contribution-resource [:vrs/contributor])]
                    {:id (str contributor-resource)
                     :type (q/ld1-> contributor-resource [:rdf/type])})
     :date (q/ld1-> contribution-resource [:vrs/date])
     :activity (let [activity-resource
                     (q/ld1-> contribution-resource [:vrs/activity])]
                 (merge {:id (str activity-resource)
                         :type (q/ld1-> activity-resource [:rdf/type])
                         #_#_:label (q/ld1-> activity-resource [:rdfs/label])}))}))

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

(defn proposition-resource-for-output
  "Takes an RDFResource for a proposition and its subject,
   and returns a map for a GA4GH Proposition"
  [proposition-resource subject-resource release-date]
  (log/debug :fn :proposition-resource-for-output
             :proposition-resource proposition-resource
             :subject-resource subject-resource
             :release-date release-date)
  (let [not-found-condition {:id (ns-cg "ConditionNotFound")
                             :type "Phenotype"}]
    (when proposition-resource
      {:type (q/ld1-> proposition-resource [:rdf/type])
       :subject (str subject-resource)
       :predicate (q/ld1-> proposition-resource [:vrs/predicate])
       :object (let [object-vof (q/ld1-> proposition-resource [:vrs/object])]
                 (log/debug :fn :proposition-resource-for-output
                            :object-vof object-vof)
               ;; with the is_version_of of the object (a trait-set), get latest
               ;; Some clinical assertions don't have any conditions
                 (if object-vof
                   (let [ts (get-trait-set-by-version-of object-vof release-date)]
                     (if ts
                       (trait-set-resource-for-output ts)
                       ;; SCV had a trait set id, but that trait set wasn't found in db
                       (do (log/error :fn :proposition-resource-for-output
                                      :msg "Statement had a trait set id, but trait set was not found"
                                      :proposition proposition-resource
                                      :object-vof object-vof)
                           not-found-condition)))
                   ;; No trait set, insert placeholder to comply with schema
                   ;; https://github.com/ga4gh/va-spec/blob/4fd8a1a07f274b6d8e19f7c69d0de0d912282e3b/schema/annotation.yaml#L393
                   (do (log/error :fn :proposition-resource-for-output
                                  :msg "Statement had no trait set id"
                                  :proposition proposition-resource
                                  :object-vof object-vof)
                       not-found-condition)))})))

(defn document-resource-for-output
  [document-resource]
  (when document-resource
    {:id (str document-resource)
     :type (q/ld1-> document-resource [:rdf/type])}))

(defn method-resource-for-output
  [method-resource]
  (when method-resource
    {:id (str method-resource)
     :type (q/ld1-> method-resource [:rdf/type])
     :label (q/ld1-> method-resource [:rdfs/label])
     :is_reported_in (let [doc-resource (q/ld1-> method-resource [:vrs/is_reported_in])]
                       (document-resource-for-output doc-resource))}))

(defn extension-resource-for-output
  [extension-resource]
  (when extension-resource
    (common/map-pop-out-lone-seq-values
     {:type (q/ld1-> extension-resource [:rdf/type])
      :name (q/ld1-> extension-resource [:vrs/name])
      :value (map #(-> % common/rdf-select-tree common/map-unnamespace-property-kw-keys)
                  (q/ld-> extension-resource [:rdf/value]))})))

(defn traits-for-output
  [traits release-date]
  (reduce (fn [vec unversioned-trait-iri]
            (let [versioned-trait-iri (str unversioned-trait-iri "." release-date)
                  trait (docstore/get-document-raw-key trait-data-db versioned-trait-iri)]
              (conj vec {:id (str "medgen:" (:medgen_id trait))
                         :type (:type trait)})))
          []
          traits))

(defn trait-set-for-output [unversioned-trait-set-iri release-date]
  (let [versioned-trait-set-iri (str unversioned-trait-set-iri "." release-date)
        trait-set (docstore/get-document-raw-key trait-set-data-db versioned-trait-set-iri)]
    {:id (:id trait-set)
     :type (:type trait-set)
     :members (traits-for-output (:members trait-set) release-date)}))

(defn clinical-assertion-for-output
  "Annotate event with data with data previously stored in rocksdb for outputting clinical assertions.
   Event is the full event stored in rocks."
  [event]
  (let [data (:genegraph.annotate/data event)]
    (assoc event :genegraph.annotate/output
           {:id (:id data)
            :type (:type data)
            :label (:label data)
            :description (:description data)
            :specified_by (:specified_by data)
            :contributions (:contributions data)
            :record_metadata (:record_metadata data)
            :direction (:direction data)
            :subject_descriptor (:subject_descriptor data)
            :extensions (:extensions data)
            :classification (:classification data)
            :target_proposition (:target_proposition data)})))

(defn clinical-assertion-resource-for-output
  ;; TODO remove keys with nil values
  ;; TODO handle :event_type delete for all these records.
  "Takes an RDFResource for a clinical assertion Statement and
   puts data it references into a GA4GH Statement structure for output."
  [assertion-resource]
  (log/info :fn :clinical-assertion-resource-for-output :assertion-resource assertion-resource)
  (let [release-date (q/ld1-> assertion-resource [:vrs/record-metadata
                                                  :owl/version-info])
        ;; Assertion is stored with the subject-descriptor just being the variation id
        ;; TODO try to speed up the variation-descriptor-by-clinvar-id function call
        subject-descriptor (variation/get-variation-descriptor-by-clinvar-id
                            (q/ld1-> assertion-resource [:vrs/subject-descriptor])
                            release-date)]
    (when (nil? subject-descriptor)
      (log/warn :fn :clinical-assertion-resource-for-output
                :msg (str/join " " ["No subject descriptor found."
                                    "This could be because the variant for this assertion"
                                    "has not been loaded yet."])))
    {:id (str assertion-resource)
     :type (q/ld1-> assertion-resource [:rdf/type])
     :label (q/ld1-> assertion-resource [:rdfs/label])
     :description (q/ld1-> assertion-resource [:vrs/description])
     :specified_by (-> (q/ld1-> assertion-resource [:vrs/method])
                       method-resource-for-output)
     :contributions (->> (q/ld-> assertion-resource [:vrs/contributions])
                         (map contribution-resource-for-output))
     :record_metadata (-> (q/ld1-> assertion-resource [:vrs/record-metadata])
                          record-metadata-resource-for-output)
     :direction (q/ld1-> assertion-resource [:vrs/direction])
     :subject_descriptor (str subject-descriptor)
     ;; TODO This object_descriptor would be nice to have
     ;;:object_descriptor nil ; do we need this ? list of disease names, per Larry (xrefs?)
     :classification (-> assertion-resource
                         (q/ld1-> [:vrs/classification])
                         classification-resource-for-output)
     :target_proposition (-> assertion-resource
                             (q/ld1-> [:vrs/target-proposition])
                             (proposition-resource-for-output
                              (some-> subject-descriptor (q/ld1-> [:vrs/canonical_variation]))
                              release-date))
     :extensions (->> (q/ld-> assertion-resource [:vrs/extensions])
                      (map extension-resource-for-output))}))

(defstate clinical-assertion-data-db
  :start (rocks/open "clinical-assertion-snapshot.db")
  :stop (rocks/close clinical-assertion-data-db))

(defn add-data-for-clinical-assertion [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        vof (str (ns-cg "SCV_Statement_") (:id assertion))
        release-date (:release_date message)
        id (str vof "." release-date)
        stmt-type (statement-type (:interpretation_description assertion))
        subject-descriptor (variation/get-variation-descriptor-by-clinvar-id
                            (get assertion :variation_id) release-date)
        data {:id id
              :type stmt-type
              :label (:title assertion)
              ;; https://github.com/clingen-data-model/genegraph/issues/697
              :extensions (let [local-key (get assertion :local_key)]
                            (log/debug :fn :add-data-for-clinical-assertion
                                       :local-key local-key
                                       :assertion assertion)
                            (common/fields-to-extension-maps
                             (select-keys assertion [:local_key])))
              :description (description event)
              :method (method event)
              :specified_by (method event)
              :contributions (contributions event)
              :record_metadata (merge {:type "RecordMetadata"
                                       :is_version_of vof
                                       :version (:release_date message)}
                                      (when (= "delete" (:event_type message))
                                        {:deleted true}))
              :direction (-> (:interpretation_description assertion)
                             normalize-clinsig-term
                             clinsig->direction)
              :subject_descriptor subject-descriptor
              #_#_:subject_descriptor (str (variation-descriptor-by-clinvar-id
                                            (get assertion :variation_id)
                                            (get message :release_date)))
              ;;:object_descriptor nil ; do we need this ?
              ;; list of disease names, per Larry (xrefs?)
              :classification (classification assertion)
              :target_proposition (let [proposition (proposition event)
                                        subject (:subject proposition)
                                        vrs-id (-> (variation/get-variation-descriptor-by-clinvar-id
                                                    subject
                                                    release-date)
                                                   (get-in [:canonical_variation :id]))
                                        object (:object proposition)
                                        trait-set (trait-set-for-output object release-date)]
                                    (assoc proposition
                                           :subject vrs-id
                                           :object trait-set))}]
    (-> event
        (assoc :genegraph.annotate/data data
               :genegraph.annotate/iri id
               :genegraph.annotate/data-db clinical-assertion-data-db
               :genegraph.annotate/data-id id)
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
    "contributor" {"@id" (str (get prefix-ns-map "vrs") "contributor")}
    "activity" {"@id" (str (get prefix-ns-map "vrs") "activity")}
    "method" {"@id" (str (get prefix-ns-map "vrs") "method")}
    "specified_by" {"@id" (str (get prefix-ns-map "vrs") "specified_by")}
    "contributions" {"@id" (str (get prefix-ns-map "vrs") "contributions")}
    "direction" {"@id" (str (get prefix-ns-map "vrs") "direction")}
    "description" {"@id" (str (get prefix-ns-map "vrs") "description")}
    "target_proposition" {"@id" (str (get prefix-ns-map "vrs") "target_proposition")}
    "subject" {"@id" (str (get prefix-ns-map "vrs") "subject")}
    "predicate" {"@id" (str (get prefix-ns-map "vrs") "predicate")}
    "object" {"@id" (str (get prefix-ns-map "vrs") "object")}
    "subject_descriptor" {"@id" (str (get prefix-ns-map "vrs") "subject_descriptor")}
    "classification" {"@id" (str (get prefix-ns-map "vrs") "classification")}
    "is_reported_in" {"@id" (str (get prefix-ns-map "vrs") "is_reported_in")}


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
    "Coding" {"@id" (str (get prefix-ns-map "vrs") "Coding")
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
    "ConditionNotProvided" {"@id" (str (get prefix-ns-map "vrs") "ConditionNotProvided")
                            "@type" "@id"}

    "Approver" {"@id" (str (get prefix-ns-map "cgterms") "Approver")
                "@type" "@id"}
    "Submitter" {"@id" (str (get prefix-ns-map "cgterms") "Submitter")
                 "@type" "@id"}

    ; Prefixes
    "rdf" {"@id" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           "@prefix" true}
    "rdfs" {"@id" "http://www.w3.org/2000/01/rdf-schema#"
            "@prefix" true}
    "vrs" {"@id" (get prefix-ns-map "vrs")
           "@prefix" true}
    "cgterms" {"@id" (get prefix-ns-map "cgterms")
               "@prefix" true}
    "@vocab" (get prefix-ns-map "cgterms")}})

(comment
  (require '[genegraph.transform.clinvar.ga4gh :as ga4gh])
  (require '[genegraph.transform.clinvar.core :as core])
  (require '[genegraph.transform.clinvar.variation :as variation])
  (require '[genegraph.transform.types :as xform-types])
  (ga4gh/start-states!)
  (def assertion "{
    \"release_date\": \"2019-07-01\",
    \"event_type\": \"create\",
    \"content\": {
                \"variation_id\": \"40347\",
                \"entity_type\": \"clinical_assertion\",
                \"variation_archive_id\": \"VCV000040347\",
                \"submitter_id\": \"26957\",
                \"date_last_updated\": \"2019-05-09\",
                \"interpretation_comments\": [\"{\\\"text\\\":\\\"The L245F variant has been published previously in association with Noonan spectrum disorders, including as an apparently de novo occurrence (Koudova et al., 2009; Sarkozy et al., 2009). It has also been confirmed to occur de novo in an individual sent to GeneDx for testing. The variant is not observed in large population cohorts (Lek et al., 2016). In-silico analyses, including protein predictors and evolutionary conservation, support a deleterious effect. A different nucleotide change leading to the same missense variant (c.735 A>T) as well as missense variants in nearby residues (T241P/R/M, T244P, A246P) have been reported in the Human Gene Mutation Database in association with cardio-facio-cutaneous syndrome (Stenson et al., 2014), supporting the functional importance of this region of the protein. In summary, we consider the variant to be pathogenic.\\\",\\\"type\\\":\\\"public\\\"}\"],
                \"interpretation_description\": \"Pathogenic\",
                \"trait_set_id\": \"9460\",
                \"internal_id\": \"95781\",
                \"content\": \"{\\\"AttributeSet\\\":{\\\"Attribute\\\":{\\\"$\\\":\\\"GeneDx Variant Classification (06012015)\\\",\\\"@Type\\\":\\\"AssertionMethod\\\"},\\\"Citation\\\":{\\\"URL\\\":{\\\"$\\\":\\\"https://submit.ncbi.nlm.nih.gov/ft/byid/7oynscmk/mdi-5616_26957_genedx_interprules_final_061215.pdf\\\"}}}}\",
                \"clingen_version\": 0,
                \"submitted_assembly\": \"GRCh37\",
                \"submission_id\": \"26957.2019-01-29\",
                \"local_key\": \"GDX:6934|Not Provided\",
                \"clinical_assertion_observation_ids\": [\"SCV000057188.0\"],
                \"assertion_type\": \"variation to disease\",
                \"rcv_accession_id\": \"RCV000033283\",
                \"clinical_assertion_trait_set_id\": \"SCV000057188\",
                \"id\": \"SCV000057188\",
                \"submission_names\": [\"SUB5098196\"],
                \"record_status\": \"current\",
                \"date_created\": \"2013-03-18\",
                \"review_status\": \"criteria provided, single submitter\",
                \"interpretation_date_last_evaluated\": \"2018-07-03\",
                \"version\": \"16\"
                }
    }")
  (def event (-> assertion
                 (json/parse-string true)
                 ga4gh/eventify
                 core/add-parsed-value)))
