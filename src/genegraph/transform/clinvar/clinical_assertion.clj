(ns genegraph.transform.clinvar.clinical-assertion
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [genegraph.sink.document-store :as document-store]))

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

(defn add-data-for-raw-trait [event]
  (let [trait (event-data event)]
    (assoc event
           :genegraph.annotate/data
           {:id (str "medgen:" (:medgen_id trait))
            :clinvar_id (:id trait)
            :clinvar_type (:entity_type trait)
            :label (:name trait)})))

(defn add-data-for-trait-set [event]
  (let [trait-set (event-data event)]
    (assoc event
           :genegraph.annotate/data
           {:clinvar_type (:entity_type trait-set)
            :clinvar_id (:id trait-set)
            :trait_ids (:trait_ids trait-set)})))

(defn proposition-target [event]
  (let [assertion (get-in event [:genegraph.transform.clinvar.core/parsed-value :content])
        get-value (partial get-from-store (::document-store/db event))
        trait_set (get-value "trait_set" (:trait_set_id assertion))
        traits (mapv #(:id (get-value "trait" %)) (:trait_ids trait_set))]
    (if (= 1 (count traits))
      (first traits)
      ;; TODO construct condition insted
      ;; consider null medgen id...
      traits)))

(defn proposition [event]
  {:subject "variationgoeshere" ; TODO clinvar:variationid for kyle
   :predicate "causes_mendelian_condition"
   :object (proposition-target event)})

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
  (let [assertion (get-in event [:genegraph.transform.clinvar.core/parsed-value :content])]
    (assoc event
           :genegraph.annotate/data
           {:id (str domain-root (:id assertion) "_" (:date_last_updated assertion))
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
            :record_metadata nil ; ClinVar Version ? Other clinvar specific fields ?
            :direction (clinsig-term->enum-value
                        (:interpretation_description assertion)
                        :direction)
            ;; TODO substitute later with variation_id + last ISO date
            :subject_descriptor (str clinvar-variation-root (:variation_id assertion))
            :variation_origin "germline"
            :object_descriptor nil ; do we need this ? list of disease names, per Larry (xrefs?)
            :classification (classification assertion)
            :target_proposition (proposition event)})))

(defn add-clinical-assertion-map [event]
  (let [assertion (get-in event [:genegraph.transform.clinvar.core/parsed-value :content])]
    (assoc event
           :genegraph.annotate/data
           {:id (str domain-root (:id assertion) "_" (:date_last_updated assertion))
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
            :record_metadata nil ; ClinVar Version ? Other clinvar specific fields ?
            :direction (clinsig-term->enum-value
                        (:interpretation_description assertion)
                        :direction)
            ;; TODO substitute later with variation_id + last ISO date
            :subject_descriptor (str clinvar-variation-root (:variation_id assertion))
            :variation_origin "germline"
            :object_descriptor nil ; do we need this ? list of disease names, per Larry (xrefs?)
            :classification (classification assertion)
            :target_proposition (proposition event)})))

;; (defn common/clinvar-add-model :clinical_assertion [event]
;;   (-> event))
