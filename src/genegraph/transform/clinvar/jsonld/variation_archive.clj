(ns genegraph.transform.clinvar.jsonld.variation-archive
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-model-to-jsonld
                                                        variation-geno-type
                                                        genegraph-kw-to-iri]]
            [genegraph.transform.clinvar.iri :as iri]))

[:date_created
 :date_last_updated
 :id
 :interp_content
 :interp_description
 :interp_type
 :num_submissions
 :num_submitters
 :record_status
 :review_status
 :species
 :variation_id
 :version
 ]
[:content
 :interp_date_last_evaluated
 :interp_explanation
 ]
(defn variation-archive-to-jsonld [msg]
  (let [id (format (str iri/variation-archive "%s.%s")
                   (:id msg)
                   (:release_date msg))
        context {"@context" {"@vocab" iri/cgterms
                             "clingen" iri/cgterms
                             "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
                             "clinvar" "https://www.ncbi.nlm.nih.gov/clinvar/"
                             }
                 "@id" id}]
    (genegraph-kw-to-iri
      (merge
        context
        {"@type" [:cg/ClinVarObject
                  (str iri/cgterms "AggregateVariantClinicalSignificanceAssertion")]
         :dc/is-version-of {"@id" (str iri/variation-archive (:id msg))}
         :dc/has-version (:version msg)


         :sepio/has-subject {"@id" (str iri/clinvar-variation (:variation_id msg))}
         :sepio/has-predicate (:interp_description msg)
         :sepio/has-object "http://purl.obolibrary.org/obo/MONDO_0000001"
         :sepio/date-created (:date_created msg)
         :sepio/date-modified (:date_last_updated msg)

         :sepio/qualified-contribution {:sepio/activity-date (:release_date msg)
                                        :sepio/has-role "ArchiverRole"
                                        :sepio/has-agent {"@id" (str iri/submitter "clinvar")}}

         ; ClinGen/ClinVar additional terms (namespaced to @vocab)
         ;"in_species"          (:species msg)
         ;"submittedCondition"          (str iri/clinical-assertion-trait-set (:clinical_assertion_trait_set_id msg))

         }
        (-> msg (dissoc :id
                        :version
                        :variation_id
                        :interp_description
                        :date_created
                        :date_last_updated
                        )
            )))))

(defmethod clinvar-model-to-jsonld :variation_archive [msg]
  (variation-archive-to-jsonld msg))
