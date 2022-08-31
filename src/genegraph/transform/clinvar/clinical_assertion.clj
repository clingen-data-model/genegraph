(ns genegraph.transform.clinvar.clinical-assertion
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [genegraph.database.names :refer [local-class-names
                                              local-property-names prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.sink.document-store :as document-store]
            [genegraph.transform.clinvar.iri :refer [ns-cg]]
            [genegraph.transform.clinvar.iri :as iri]
            [io.pedestal.log :as log]))

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

(defn classification [assertion]
  {:id (clinsig-term->enum-value (:interpretation_description assertion)
                                 :classification)
   :type "Coding"
   :label (:interpretation_description assertion)})

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
        trait (:content message)]
    (log/info :event event)
    (log/info :trait trait)
    (-> event
        (assoc
         :genegraph.annotate/data
         {:id (cond
                (:medgen_id trait) (str "medgen:" (:medgen_id trait))
                :else (:id trait))
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
  (let [rs (q/select "select ?i where {
                      { ?i a :vrs/Disease } union { ?i a :vrs/Phenotype }
                      ?i :cg/clinvar-trait-id ?trait_id .
                      ?i :cg/release-date ?release_date }"
                     {:trait_id trait-id
                      :release_date release-date})]
    (when (and (< 1 (count rs))
               (< 1 (count (set (map :id rs)))))
      (log/error :fn :get-trait-byid
                 :msg "Multiple matching traits with different identifiers"
                 :trait-id trait-id
                 :release-date release-date
                 :rs (map str rs)))
    (first rs)))

(defn trait-resource-for-output [trait]
  {:id (str trait)
   :type (str (q/ld1-> trait [:rdf/type]))})

(defn add-data-for-trait-set [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        trait-set (:content message)]
    (-> (assoc
         event
         :genegraph.annotate/data
         {:id (str (ns-cg "trait_set_") (:id trait-set) "." (:release_date message))
          :type "Condition"
          :clinvar_trait_set_id (:id trait-set)
          :release_date (:release_date message)
          :members (let [trait-ids (:trait_ids trait-set)]
                     (log/info :fn :add-data-for-trait-set
                               :trait-ids trait-ids)
                     (->> trait-ids
                          (map #(get-trait-by-id % (:release_date message)))
                          (map #(trait-resource-for-output %))))}
         :genegraph.annotate/iri
         (str (ns-cg "trait_set_") (:id trait-set) "." (:release_date message)))
        add-contextualized)))

(defn trait-set-resource-for-output
  "Takes an RDFResource for a trait-set object (:vrs/Condition)"
  [trait-set]
  {:id (str trait-set)
   :type "Condition"
   :members (->> (q/ld-> trait-set [:vrs/members])
                 (map #(get-trait-by-id % (q/ld1-> trait-set [:cg/release-date])))
                 (map #(trait-resource-for-output %)))})

(defn get-trait-set-by-id [trait-set-id release-date]
  (log/info :fn :get-trait-set-by-id
            :trait-set-id trait-set-id
            :release-date release-date)
  (let [rs (q/select "select ?i where {
                      ?i a :vrs/Condition .
                      ?i :cg/clinvar-trait-set-id ?trait_set_id .
                      ?i :cg/release-date ?release_date }"
                     {:trait_set_id trait-set-id
                      :release_date release-date})]
    (when (and (< 1 (count rs))
               (< 1 (count (set (map :id rs)))))
      (log/error :fn :get-trait-byid
                 :msg "Multiple matching trait sets with different identifiers"
                 :trait-set-id trait-set-id
                 :release-date release-date
                 :rs (map str rs)))
    (first rs)))

(defn proposition-object [event]
  (log/info :fn :proposition-object :event event)
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)
        trait-set-id (:trait_set_id assertion)]
    (get-trait-set-by-id trait-set-id (:release_date message))))

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

(defn proposition [event]
  (let [subject-descriptor (variation-descriptor-by-clinvar-id
                            (get-in event [:genegraph.transform.clinvar.core/parsed-value
                                           :content
                                           :variation_id])
                            (get-in event [:genegraph.transform.clinvar.core/parsed-value
                                           :release_date]))
        subject (q/ld1-> subject-descriptor [:rdf/value])]
    {:subject (str subject)
     :predicate "causes_mendelian_condition"
     :object (str (proposition-object event))}))

(defn description [event]
  (when-let [interpretation-comments
             (get-in
              event
              [:genegraph.transform.clinvar.core/parsed-value
               :content
               :interpretation_comments])]
    (-> (first interpretation-comments)
        (json/parse-string true)
        :text)))


(defn add-data-for-clinical-assertion [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        assertion (:content message)]
    (-> (assoc
         event
         :genegraph.annotate/data
         {:id (str domain-root (:id assertion) "." (:release_date message) #_(:date_last_updated assertion))
          :type "VariationGermlineConditionStatement"
          :label (:title assertion)
            ;; Loop around on needed data to include in extensions later
          :extensions nil
            ;; Don't think we've settled on description
          :description (description event)
            ;; Removing strength per discussion with AW 2022-08-23 =tristan
          #_:strength #_(clinsig-term->enum-value
                         (:interpretation_description assertion)
                         :strength)
            ;; I think not relevant here
          :confidence_score nil
            ;; Method is per-submitter in ClinVar, not per assertion (I think)
            ;; have we considered how to represent methods? =tristan
          :method nil
          :contributions nil ; Should be a standard form for contribution here
          :is_reported_in nil ; ClinVar?
          :record_metadata {:is_version_of ()
                            :version (:release_date message)}
          :direction (clinsig-term->enum-value
                      (:interpretation_description assertion)
                      :direction)
            ;; TODO substitute later with variation_id + last ISO date
          :subject_descriptor (str (variation-descriptor-by-clinvar-id
                                    (get-in event [:genegraph.transform.clinvar.core/parsed-value
                                                   :content
                                                   :variation_id])
                                    (get-in event [:genegraph.transform.clinvar.core/parsed-value
                                                   :release_date])))
          :variation_origin "germline"
          :object_descriptor nil ; do we need this ? list of disease names, per Larry (xrefs?)
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
