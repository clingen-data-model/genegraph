(ns genegraph.transform.clinvar.jsonld.clinical-assertion
  (:require [genegraph.database.load :as l]
            [genegraph.database.names :refer [local-property-names local-class-names prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-to-jsonld
                                                        variation-geno-type
                                                        genegraph-kw-to-iri
                                                        vcv-review-status-to-evidence-strength-map
                                                        scv-review-status-to-evidence-strength-map]]
            [genegraph.transform.clinvar.iri :as iri]
            [taoensso.timbre :as log]))

(defn clinical-assertion-to-jsonld [msg]
  (let [id (format (str iri/clinvar-assertion "%s.%s")
                   (:id msg)
                   (:release_date msg))
        rdf-type (str iri/cgterms "VariantClinicalSignificanceAssertion")
        context {"@context" {"@vocab"          iri/cgterms
                             "clingen"         iri/cgterms
                             "sepio"           "http://purl.obolibrary.org/obo/SEPIO_"
                             "clinvar"         "https://www.ncbi.nlm.nih.gov/clinvar/"
                             rdf-type          {"@type" "@id"}
                             :cg/ClinVarObject {"@type" "@id"}
                             ;"entity_type"        {"@id"   "@type"
                             ;                      "@type" "@vocab"}
                             ;"clinical_assertion" rdf-type

                             }
                 "@id"      id}]
    (genegraph-kw-to-iri
      (merge
        context
        {:rdf/type                     [{"@id" :cg/ClinVarObject}
                                        {"@id" rdf-type}]
         :dc/is-version-of             (str iri/clinvar-assertion (:id msg))
         :dc/has-version               (:version msg)
         :dc/title                     (:title msg)

         :sepio/has-subject            (str iri/clinvar-variation (:variation_id msg))
         :sepio/has-predicate          (:interpretation_description msg)
         :sepio/has-object             (str iri/trait-set (:trait_set_id msg))
         :sepio/date-created           (:date_created msg)
         :sepio/date-modified          (:date_last_updated msg)
         :sepio/qualified-contribution {:sepio/activity-date (:interpretation_date_last_evaluated msg)
                                        :sepio/has-role      "SubmitterRole"}

         ; Reverse relation to parent variation archive
         ;"@reverse"                    {:sepio/has-evidence-line [{:sepio/has-evidence-item      id
         ;                                                          :sepio/has-evidence-direction "supports"
         ;                                                          :sepio/evidence-line-strength (scv-review-status-to-evidence-strength-map
         ;                                                                                          (:review_status msg))}]}

         ; ClinGen/ClinVar additional terms (namespaced to @vocab)
         "hasReviewStatus"             (:review_status msg)
         "submittedCondition"          (str iri/clinical-assertion-trait-set (:clinical_assertion_trait_set_id msg))
         ; TODO add allele origin from observations to the submitted variation
         ; TODO update field name if change occurs here https://github.com/clingen-data-model/clinvar-streams/issues/3
         "submittedVariation"          (:clinical_assertion_variations msg)
         ; "hasCollectionMethod" (get-in obs ...)
         }
        (-> msg
            (dissoc
              :id
              :version
              :title
              :variation_id
              :interpretation_description
              :trait_set_id
              :date_created
              :date_last_updated
              :interpretation_date_last_evaluated
              :review_status
              :clinical_assertion_trait_set_id
              :clinical_assertion_variations))))))

(defmethod clinvar-to-jsonld :clinical_assertion [msg]
  (clinical-assertion-to-jsonld msg))

;(defmethod transform-clinvar :clinical_assertion [msg]
;  (let [content (:content msg)
;        iri (str iri/clinvar-assertion (:id content) "_" (:release_date msg) "_" (:clingen_version msg))
;        contribution-iri (q/resource (str iri "_contribution"))
;        ]
;    (concat
;      [[iri :rdf/type :cg/VariantClinicalSignificanceClassification]
;       ; TODO if acmg-like add VariantPathogenicityInterpretation
;
;       ; TODO maybe use rdf/type
;       [iri :cg/assertion-type (:assertion_type content)]
;
;       [iri :sepio/date-created (:date_created content)]
;       [iri :sepio/date-modified (:date_last_updated content)]
;       [iri :cv/internal-identifier (:internal_id content)] ; Clinvar db id?
;       [iri :oboInOwl/database-cross-reference (:local_key content)] ; Uploader-specific, db-specific
;
;       [iri :sepio/has-subject (q/resource (str iri/clinvar-variation (:variation_id content)))]
;       [iri :sepio/created-by (q/resource (str iri/submitter (:submitter_id content)))]
;
;       [iri :sepio/has-predicate (:interpretation_description content)] ; TODO use GENO terms for predicate
;       ; use general terms for the above, or "unknown"
;       [iri :cv/has-clinvar-interpretation (:interpretation_description content)] ; TODO
;
;       [iri :sepio/has-object (q/resource (str iri/trait-set (:trait_set_id content)))]
;
;       [iri :dc/is-referenced-by (q/resource (str iri/rcv (:rcv_accession_id content)))]
;       [iri :dc/is-referenced-by (q/resource (str iri/variation-archive (:variation_archive_id content)))]
;
;       [iri :cv/record-status (:record_status content)]
;       [iri :cv/review-status (:review_status content)]
;       [iri :dc/has-version (:version content)]
;       [iri :cg/additional-content (:content content)]
;
;       [iri :dc/title (:title content)]
;
;       ; Contribution (submitted fields)
;       [iri :sepio/qualified-contribution contribution-iri]
;       ; TODO type? interpretation vs classification?
;       [contribution-iri :sepio/activity-date (:interpretation_date_last_evaluated content "")]
;       [contribution-iri :sepio/has-object (:clinical_assertion_trait_set_id content)]
;       [contribution-iri :so/assembly (:submitted_assembly content)]
;       [contribution-iri :dc/identifier (:submission_id content)]
;
;       ]
;      (into [] (map #(vector iri :sepio/has-evidence-line %)
;                    (:interpretation_comments content)))
;      (into [] (map #(vector contribution-iri :sepio/has-evidence-line %)
;                    (:clinical_assertion_observation_ids content)))
;      (into [] (map #(vector contribution-iri :shacl/name %)
;                    (:submission_names content)))
;      )))

;(defmethod transform-clinvar :clinical_assertion_observation [msg]
;  (let [content (:content msg)
;        iri (str iri/clinical-assertion-observation
;                 (:id content) "_"
;                 (:release_date msg) "_"
;                 (:clingen_version msg))]
;    (concat
;      [[iri :cg/additional-content (:content content)]
;       [iri :cg/has-clinical-assertion-trait-set (q/resource
;                                                   (str iri/clinical-assertion-trait-set
;                                                        (:clinical_assertion_trait_set_id content)))]
;       ]
;      )))

;[:clinical_assertion_trait_ids
; :id
; :type]
;[:content]
;(defmethod transform-clinvar :clinical_assertion_trait_set [msg]
;  (let [content (:content msg)
;        iri (str iri/clinical-assertion-trait-set
;                 (:id content) "_"
;                 (:release_date msg) "_"
;                 (:clingen_version msg))]
;    (concat
;      [
;       [iri :cv/trait-type (:type content)]
;       [iri :cg/additional-content (:content content)]
;       ]
;      (into [] (map #(vector iri :geno/related-condition %)
;                    (:clinical_assertion_trait_ids content)))
;      )))
;
;[:alternate_names
; :id
; :type
; :xrefs]
;[:content
; :medgen_id
; :name
; :trait_id]
;(defmethod transform-clinvar :clinical_assertion_trait [msg]
;  (let [content (:content msg)
;        iri (str iri/clinical-assertion-trait
;                 (:id content) "_"
;                 (:release_date msg) "_"
;                 (:clingen_version msg))]
;    (log/info content)
;    (concat
;      [
;       [iri :cv/trait-type (:type content)]
;       [iri :cg/additional-content (:content content)]
;       [iri :medgen/id (:medgen_id content)]
;       [iri :cg/has-preferred-name (:name content)]
;       [iri :cv/trait-id (:trait_id content)]
;       ]
;      (into [] (map #(vector iri :cg/has-alternate-name %)
;                    (:alternate_names content)))
;      )))
;
;
;[:child_ids
; :clinical_assertion_id
; :descendant_ids
; :id
; :subclass_type]
;[:content
; :variation_type]
;(defmethod transform-clinvar :clinical_assertion_variation [msg]
;  (let [content (:content msg)
;        iri (str iri/clinical-assertion-variation
;                 (:id content) "_"
;                 (:release_date msg) "_"
;                 (:clingen_version msg))]
;    (concat
;      [
;       [iri :rdf/type (variation-geno-type (:subclass_type content))]
;       [iri :cg/has-assertion (q/resource
;                                (str iri/clinvar-assertion (:clinical_assertion_id content)))]
;       [iri :cg/additional-content (:content content)]
;       [iri :cg/variation-type (:variation_type content)]
;       ]
;      (into [] (map #(vector iri :cg/has-child %)
;                    (:child_ids content)))
;      (into [] (map #(vector iri :cg/has-descendant %)
;                    (:descendant_ids content)))
;      )))