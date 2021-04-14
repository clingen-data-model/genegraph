(ns genegraph.transform.clinvar.jsonld.clinical-assertion
  (:require [genegraph.database.load :as l]
            [genegraph.database.names :refer [local-property-names local-class-names prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-to-jsonld
                                                        variation-geno-type
                                                        genegraph-kw-to-iri
                                                        vcv-review-status-to-evidence-strength-map
                                                        scv-review-status-to-evidence-strength-map
                                                        consensus-cancer-genes-by-symbol]]
            [genegraph.transform.clinvar.iri :as iri]
            [io.pedestal.log :as log]))

(defn add-clingen-classification-context
  "Expects clinical-assertion to be a message passed in from clinvar-streams.

   or the JSON-LD representation of the assertion with fields as transformed by
   genegraph.transform.clinvar.jsonld.clinical-assertion/clinical-assertion-to-jsonld.
   as long as required fields here are present.

  Returns the object with key :cg/classification-context added, which maps into property-names.edn

  Classification Context Binning Rules are Applied in the Following Order:
  Somatic Cancer Classification Context Binning Rule Requirements
    Allele origin must be exclusively [somatic] (regardless of clinsig).
      For single gene SCVs, must be associated to a gene on the “somatic cancer” list provided by Alex Wagner
      For multiple gene SCVs, - clarify with Heidi
  Pharmacogenomic Classification Context Binning Rule Requirements
    Clinsig must be [drug response]
      Note: As Somatic Cancer rule was already applied, SCVs with clinsig of drug response that meet requirements above will be binned as Somatic Cancer. “Classification Context”
  Germline Disease Classification Context Binning Rule Requirements
    When allele origin is anything but somatic only AND clinsig is [Path-Benign OR risk factor]
  Other Classification Context Binning Rule Requirements
    A record not meeting any of the above rules, will be binned as other for the purpose of this exercise. This includes:
      Allele origin germline, with clinsig other than Path-Benign/risk factor
      Any allele origin not listed above

  "
  [clinical-assertion]
  (letfn [(in? [coll e]
            (some #(= % e) coll))
          (compute-classification-context [clinical-assertion]
            (let [allele-origins (:allele_origins clinical-assertion)
                  clinsig (:interpretation_description clinical-assertion)]
              (cond (in? (keys consensus-cancer-genes-by-symbol) (:genes clinical-assertion))
                    (log/info :msg "Is somatic cancer variant")

                    )))]
    clinical-assertion))

(defn clinical-assertion-to-jsonld [msg]
  (let [id (format (str iri/clinvar-assertion "%s.%s")
                   (:id msg)
                   (:release_date msg))
        evidence-line-id (str iri/cgterms "evidence_line/" (:id msg))
        assertion-rdf-type (str iri/cgterms "VariantClinicalSignificanceAssertion")
        context {"@context" {"@vocab" iri/cgterms
                             "clingen" iri/cgterms
                             "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
                             "clinvar" "https://www.ncbi.nlm.nih.gov/clinvar/"
                             ;rdf-type          {"@type" "@id"}
                             ;:cg/ClinVarObject {"@type" "@id"}
                             }
                 }
        msg (add-clingen-classification-context msg)
        ]
    (genegraph-kw-to-iri
      (merge
        context
        ; TODO Add @base
        {"@type" [:cg/ClinVarObject
                  (str iri/cgterms "EvidenceLine")],
         "@id" evidence-line-id,

         :sepio/has-evidence-direction "supports"
         :sepio/evidence-line-strength (scv-review-status-to-evidence-strength-map
                                         (:review_status msg))
         ; The SCV itself is an evidence item within an evidence line that pertains to a variant assertion
         :sepio/has-evidence-item
         (merge
           {"@type" [:cg/ClinVarObject
                     assertion-rdf-type]
            "@id" id
            :dc/is-version-of {"@id" (str iri/clinvar-assertion (:id msg))}
            :dc/has-version (:version msg)
            :dc/title (:title msg)

            :sepio/has-subject {"@id" (str iri/clinvar-variation (:variation_id msg))}
            :sepio/has-predicate (:interpretation_description msg)
            :sepio/has-object (str iri/trait-set (:trait_set_id msg))
            :sepio/date-created (:date_created msg)
            :sepio/date-updated (:date_last_updated msg)

            :sepio/qualified-contribution {:sepio/activity-date (:interpretation_date_last_evaluated msg)
                                           :sepio/has-role "SubmitterRole"
                                           :sepio/has-agent {"@id" (str iri/submitter (:submitter_id msg))}}

            ; ClinGen/ClinVar additional renamed terms (namespaced to @vocab)
            "allele_origin" (:allele_origins msg)
            "collection_method" (:collection_methods msg)
            "submitted_condition" (str iri/clinical-assertion-trait-set (:clinical_assertion_trait_set_id msg))
            ; TODO add allele origin from observations to the submitted variation
            ; TODO update field name if change occurs here https://github.com/clingen-data-model/clinvar-streams/issues/3
            "submitted_variation" (:clinical_assertion_variations msg)
            }
           (-> msg (dissoc
                     :id
                     :version
                     :title
                     :variation_id
                     :interpretation_description
                     :trait_set_id
                     :date_created
                     :date_last_updated
                     :interpretation_date_last_evaluated
                     :submitter_id
                     :allele_origins
                     :collection_methods
                     :clinical_assertion_trait_set_id
                     :clinical_assertion_variations))), ; End assertion

         ; Reverse relation from evidence line to parent variation archive
         "@reverse" {:sepio/has-evidence-line
                     [{"@id" (let [variation-archive-iri (format (str iri/variation-archive "%s")
                                                                 (:variation_archive_id msg))]
                               (if (empty? (:variation_archive_id msg))
                                 (throw (ex-info "Variation archive id was null" {:cause msg})))
                               (log/debug :msg (format "Evidence line %s has reverse relation to %s"
                                                       evidence-line-id variation-archive-iri))
                               variation-archive-iri)}]}
         }
        ))))

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
