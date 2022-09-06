(ns genegraph.transform.clinvar.clinical-assertion
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [genegraph.database.load :as l]
            [genegraph.database.names :refer [local-class-names
                                              local-property-names prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.sink.document-store :as document-store]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [genegraph.transform.clinvar.util :refer [in?]]
            [io.pedestal.log :as log])
  (:import (genegraph.database.query.types RDFResource)))

(declare statement-context)

(def clinsig-values
  ["likely benign"
   "association"
   "benign"
   "risk factor"
   "uncertain significance"
   "likely pathogenic"
   "pathologic"
   "pathogenic"
   "protective"
   "non-pathogenic"
   "no known pathogenicity"
   "other"
   "not provided"
   "drug response"
   "variant of unknown significance"])

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
               "variant of unknown significance" "uncertain"

               ;; Revisit what risk factor means
               ;; "risk factor" "supports"

               ;; not provided likely = nil, but keeping these
               ;; around until that can be ascertained.
               ;; "not provided" "not provided"
               ;; "drug response" "not provided"
               ;; "other" "not provided"
               ;; "protective" "not provided"
               ;; "association" "not provided"
               }
   ;; strength not currently being used, consider removing =tristan
   :strength {"likely benign" "likely"
              "benign" "strong"

              "uncertain significance" "uncertain"
              "likely pathogenic" "likely"
              "pathologic" "strong"
              "pathogenic" "strong"

              "non-pathogenic" "strong"
              "no known pathogenicity" "strong"
              "variant of unknown significance" "uncertain"

              ;; Per above, need to understand what risk factor means
              ;; "risk factor"

              ;; not providing values for non-germline assertions
              ;; "association"
              ;; "protective"
              ;; "other"
              ;; "not provided"
              ;; "drug response"
              }
   :classification {"likely benign" "loinc:LA26334-5"
                    "benign" "loinc:LA6675-8"
                    "uncertain significance" "loinc:LA26333-7"
                    "variant of unknown significance" "loinc:LA26333-7"
                    "likely pathogenic" "loinc:LA26332-9"
                    "pathologic" "loinc:LA6668-3"
                    "pathogenic" "loinc:LA6668-3"
                    "non-pathogenic" "loinc:LA6675-8"
                    "no known pathogenicity" "loinc:LA6675-8"
                    ;; "risk factor"
                    ;; "association"
                    ;; "protective"
                    ;; "other"
                    ;; "not provided"
                    ;; "drug response"
                    }})




(def domain-root "clingen:")
(def clinvar-variation-root "clinvar-variation:")

(defn get-from-store [db type id]
  (document-store/get-document db (str type "_" id)))

(defn clinsig-term->enum-value [term field]
  (get-in clinsig->germline-statement-attribute [field (s/lower-case term)]))

(defn normalize-clinsig-term
  "Returns the mapped normalized term for the raw input term.
   If the term is not known, return the mapping for 'other'"
  [term]
  (get common/normalize-clinsig-map (s/lower-case term)
       (get common/normalize-clinsig-map "other")))

(defn get-clinsig-class
  "Returns the class of a normalized clinsig term
   e.g. path, oth, rf"
  [clinsig]
  (get common/clinsig-class-map clinsig
       (get common/clinsig-class-map "Other")))

(defn classification [assertion]
  (let [clinsig (-> assertion :interpretation_description normalize-clinsig-term)
        qualified (str (get prefix-ns-map "cgterms") clinsig)]
    {:id qualified
     :type "Coding"
     :label (-> (normalize-clinsig-term (:interpretation_description assertion))
                (s/replace " " "_")
                (s/replace "/" "_"))}))

(defn event-data [event]
  (get-in event [:genegraph.transform.clinvar.core/parsed-value :content]))

#_(defn add-data-for-raw-trait [event]
    (let [trait (event-data event)]
      (assoc event
             :genegraph.annotate/data
             {:id (str "medgen:" (:medgen_id trait))
              :clinvar_id (:id trait)
              :clinvar_type (:entity_type trait)
              :label (:name trait)})))

#_(defn add-data-for-trait-set [event]
    (let [trait-set (event-data event)]
      (assoc event
             :genegraph.annotate/data
             {:clinvar_type (:entity_type trait-set)
              :clinvar_id (:id trait-set)
              :trait_ids (:trait_ids trait-set)})))

(defn add-contextualized [event]
  (let [data (:genegraph.annotate/data event)]
    (assoc event
           :genegraph.annotate/data-contextualized
           (merge data statement-context))))

(defn add-data-for-trait [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        trait (:content message)
        fallback-id (str (ns-cg "trait") "_" (:id trait) "." (:release_date message))]
    (-> event
        (assoc
         :genegraph.annotate/data
         {:id (cond
                (:medgen_id trait) (str "medgen:" (:medgen_id trait))
                :else (do (log/warn :fn :add-data-for-trait
                                    :msg "Trait with no medgen id"
                                    :message message)
                          fallback-id))
          :type (case (:type trait)
                  "Disease" "Disease"
                  "Phenotype")
          :clinvar_trait_id (:id trait)
          :release_date (:release_date message)})
        (assoc
         :genegraph.annotate/iri
         (str (ns-cg "trait_") (:id trait) "." (:release_date message)))
        add-contextualized)))


(defn get-trait-by-id [trait-id release-date]
  (let [;; Most recent trait with trait-id no later than release-date
        rs (q/select "select ?i where {
                      { ?i a :vrs/Disease } union { ?i a :vrs/Phenotype }
                      ?i :cg/clinvar-trait-id ?trait_id .
                      ?i :cg/release-date ?release_date .
                      FILTER(?release_date <= ?max_release_date) }
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

(defn trait-resource-for-output [trait]
  (when trait
    {:id (str trait)
     :type (str (q/ld1-> trait [:rdf/type]))}))

(defn compact-one-element-condition
  "If the condition has a members array with only one element, return that element.
   (copies over :id, :clinvar_trait_set_id, :release_date)"
  [condition]
  (if (= 1 (count (:members condition)))
    (-> condition :members first
        (merge (select-keys condition [:id
                                       :clinvar_trait_set_id
                                       :release_date])))
    condition))

(defn add-data-for-trait-set [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        trait-set (:content message)
        data (->
              {:id (str (ns-cg "trait_set_") (:id trait-set) "." (:release_date message))
               :type "Condition"
               :clinvar_trait_set_id (:id trait-set)
               :release_date (:release_date message)
               :members (let [trait-ids (:trait_ids trait-set)]
                          (log/debug :fn :add-data-for-trait-set
                                     :trait-ids trait-ids)
                          (->> trait-ids
                               (map #(get-trait-by-id % (:release_date message)))
                               ((fn [traits]
                                  (when (some #(= nil %) traits)
                                    (log/error :msg "Got nil traits"
                                               :trait-set trait-set
                                               :trait-ids trait-ids
                                               :traits traits
                                               :release-date (:release_date message)))
                                  traits))
                               (map #(trait-resource-for-output %))))}
              (compact-one-element-condition))]
    (-> (assoc
         event
         :genegraph.annotate/data
         data
         :genegraph.annotate/iri
         (str (ns-cg "trait_set_") (:id trait-set) "." (:release_date message)))
        add-contextualized)))

(defn trait-set-resource-for-output
  "Takes an RDFResource for a normalized trait-set object (:vrs/Condition).
   Uses :vrs/members relationship to get the traits in it."
  [trait-set]
  (let [trait-set-type (q/ld1-> trait-set [:rdf/type])]
    (case (s/replace (str trait-set-type) (get prefix-ns-map "vrs") "")
      "Disease" {:id (str trait-set)
                 :type (str trait-set-type)}
      "Phenotype" {:id (str trait-set)
                   :type (str trait-set-type)}
      "Condition" {:id (str trait-set)
                   :type "Condition"
                   :members (->> (q/ld-> trait-set [:vrs/members])
                                 #_(map #(get-trait-by-id % (q/ld1-> trait-set [:cg/release-date])))
                                 (map #(trait-resource-for-output %)))}
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
                      ?i :cg/clinvar-trait-set-id ?trait_set_id .
                      ?i :cg/release-date ?release_date .
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

(defn ^RDFResource proposition-object [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        trait-set-id (:trait_set_id assertion)]
    (if (not (nil? trait-set-id))
      (let [trait-set-resource (get-trait-set-by-id trait-set-id (:release_date message))]
        trait-set-resource)
      (log/warn :fn :proposition-object :msg :nil-trait-set-id :message message))))

(defn variation-descriptor-by-clinvar-id [clinvar-id release-date]
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
  {"VariationGermlinePathogenicityStatement" "VariationGermlinePathogenicityPropostion"
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
        subject-descriptor (variation-descriptor-by-clinvar-id
                            (get assertion :variation_id)
                            (get message :release_date))
        stmt-type (statement-type (:interpretation_description assertion))]
    {:type (get statement-type-to-proposition-type stmt-type)
     :subject (if (not (nil? subject-descriptor))
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
     :object (let [;; Condition, Disease, Phenotype
                   proposition-object-resource (proposition-object event)]
               (if proposition-object-resource
                 (trait-set-resource-for-output proposition-object-resource)
                 ;; Not Provided (or some error in upstream trait mapping)
                 {:id (str (ns-cg "not_provided"))
                  :type "Phenotype"}))}))


(defn method
  "Returns a Variant Annotation : Method object for a clinical_assertion"
  [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        has-method? (= "AssertionMethod"
                       (get-in assertion [:content "AttributeSet" "Attribute" "@Type"]))
        method-label (get-in assertion [:content "AttributeSet" "Attribute" "$"])
        method-url (get-in assertion [:content "AttributeSet" "Citation" "URL" "$"])
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
                        :id (or method-url (str "_:" (l/blank-node)))
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
    [;; Approver (maybe an evaluator role?)
     {:type "Contribution"
      :agent qualified-submitter
      :date (:interpretation_date_last_evaluated assertion)
      :role "Approver"}
     ;; Submitter
     {:type "Contribution"
      :agent qualified-submitter
      :date (:date_last_updated assertion)
      :role "Submitter"}]))

(defn add-data-for-clinical-assertion [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        vof (str (ns-cg "SCV_Statement_") (:id assertion))
        id (str vof "." (:release_date message))
        stmt-type (statement-type (:interpretation_description assertion))]
    (-> (assoc
         event
         :genegraph.annotate/data
         {:id id
          :type stmt-type
          :label (:title assertion)
          ;; Loop around on needed data to include in extensions later
          ;; TODO
          ;;:extensions nil
          :description (description event)
          ;; Removing strength per discussion with AW 2022-08-23 =tristan
          #_:strength #_(clinsig-term->enum-value
                         (:interpretation_description assertion)
                         :strength)
          ;; I think not relevant here
          ;;:confidence_score nil
          :method (method event)
          :contributions (contributions event)
          ;;:is_reported_in nil ; ClinVar?
          :record_metadata {:is_version_of vof
                            :version (:release_date message)}
          :direction (clinsig-term->enum-value
                      (:interpretation_description assertion)
                      :direction)
          :subject_descriptor (str (variation-descriptor-by-clinvar-id
                                    (get assertion :variation_id)
                                    (get message :release_date)))
          ;;:variation_origin "germline"
          :object_descriptor nil ; do we need this ? list of disease names, per Larry (xrefs?)
          :classification (classification assertion)
          :target_proposition (proposition event)}
         :genegraph.annotate/iri
         (str (ns-cg "clinical_assertion_") (:id assertion) "." (:release_date message)))
        add-contextualized)))

(comment
  '(defn clinical-assertion-resource-for-output
     [assertion]
     {:id (str assertion)
      :type (statement-type (q/ld1-> assertion [:cg/interpretation_description]))
      :label (q/ld1-> assertion [:cg/title])
          ;; Loop around on needed data to include in extensions later
      :extensions nil
      :description (description event)
          ;; Removing strength per discussion with AW 2022-08-23 =tristan
      #_:strength #_(clinsig-term->enum-value
                     (:interpretation_description assertion)
                     :strength)
          ;; I think not relevant here
          ;;:confidence_score nil
      :method (method event)
      :contributions (contributions event)
      :is_reported_in nil ; ClinVar?
      :record_metadata {:is_version_of vof
                        :version (:release_date message)}
      :direction (clinsig-term->enum-value
                  (:interpretation_description assertion)
                  :direction)
      :subject_descriptor (str (variation-descriptor-by-clinvar-id
                                (get assertion :variation_id)
                                (get message :release_date)))
          ;;:variation_origin "germline"
      :object_descriptor nil ; do we need this ? list of disease names, per Larry (xrefs?)
      :classification (classification assertion)
      :target_proposition (proposition event)}))

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

    ;"value" {"@id" "@value"}
    "value" {"@id" "rdf:value"}

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
    "role" {"@id" (str (get prefix-ns-map "vrs") "role")}

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
