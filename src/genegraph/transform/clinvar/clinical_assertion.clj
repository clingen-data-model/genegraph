(ns genegraph.transform.clinvar.jsonld.clinical-assertion
  (:require [cheshire.core :as json]
            [clojure.string :as s]))

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


;; 
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


(def an-assertion
  {:release_date "2019-07-01",
   :event_type "create",
   :content
   {:variation_id "8081",
    :entity_type "clinical_assertion",
    :variation_archive_id "VCV000008081",
    :submitter_id "3",
    :date_last_updated "2019-03-31",
    :interpretation_comments [],
    :interpretation_description "Pathogenic",
    :trait_set_id "2199",
    :internal_id "28756",
    :clingen_version 0,
    :submission_id "3.2010-12-30",
    :local_key "601545.0009_SUBCORTICAL LAMINAR HETEROTOPIA",
    :clinical_assertion_observation_ids ["SCV000028756.0"],
    :title "PAFAH1B1, ARG8TER_SUBCORTICAL LAMINAR HETEROTOPIA",
    :assertion_type "variation to disease",
    :rcv_accession_id "RCV000008548",
    :clinical_assertion_trait_set_id "SCV000028756",
    :id "SCV000028756",
    :submission_names [],
    :record_status "current",
    :date_created "2011-01-25",
    :review_status "no assertion criteria provided",
    :interpretation_date_last_evaluated "2003-10-28",
    :version "1"}})

(def domain-root "clingen:")
(def clinvar-variation-root "clinvar-variation:")

(defn clinsig-term->enum-value [term field]
  (get-in clinsig->germline-statement-attribute [field (s/lower-case term)]))

(defn classification [assertion]
  {:id (clinsig-term->enum-value (:interpretation_description assertion)
                                              :classification)
   :type "Coding"
   :label (:interpretation_description assertion)})

(defn clinvar-raw-germline-assertion->va-germline-assertion [assertion]
  {:id (str domain-root (:id assertion) "_" (:date_last_updated assertion))
   :type "VariationGermlineConditionStatement"
   :label (:title assertion)
   ;; Loop around on needed data to include in extensions later
   :extensions nil
   ;; Don't think we've settled on description
   :description nil
   :strength (clinsig-term->enum-value
              (:interpretation_description assertion)
              :strength)
   ;; I think not relevant here
   :confidence_score nil
   ;; Method is per-submitter in ClinVar, not per assertion (I think)
   ;; have we considered how to represent methods? =tristan
   :method nil
   :contributions nil ; Should be a standard form for contribution here
   :is_reported_in nil ; ClinVar?
   :record_metadata nil ; ClinVar Version ? Other clinvar specific fields ? 
   :direction (clinsig-term->enum-value
               (:interpretation_description assertion)
               :direction)
   ;; TODO substitute later with variation_id + last ISO date
   :subject_descriptor (str clinvar-variation-root (:variation_id assertion))
   :variation_origin "germline"
   :object_descriptor nil ; do we need this ?
   :classification (classification assertion)
   :target_proposition nil
   })

(clinvar-raw-germline-assertion->va-germline-assertion (:content an-assertion))
