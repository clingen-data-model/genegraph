(ns genegraph.transform.clinvar.jsonld.variation-archive
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-to-jsonld
                                                        variation-geno-type
                                                        genegraph-kw-to-iri]]
            [genegraph.transform.clinvar.iri :as iri]
            [taoensso.timbre :as log]))

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
  (let [context {"@context" {"@vocab"  iri/cgterms
                             "clingen" iri/cgterms
                             "sepio"   "http://purl.obolibrary.org/obo/SEPIO_"
                             "clinvar" "https://www.ncbi.nlm.nih.gov/clinvar/"
                             }
                 "@id"      (format (str iri/variation-archive "%s.%s")
                                    (:id msg)
                                    (:release_date msg))}]
    (genegraph-kw-to-iri
      (merge
        context
        {:rdf/type            (str iri/cgterms "AggregateVariantClinicalSignificanceAssertion")
         :dc/is-version-of    (str iri/variation-archive (:id msg))
         :dc/has-version      (:version msg)


         :sepio/has-subject   (str iri/clinvar-variation (:variation_id msg))
         :sepio/has-predicate (:interp_description msg)
         :sepio/has-object    "http://purl.obolibrary.org/obo/MONDO_0000001"
         :sepio/date-created  (:date_created msg)
         :sepio/date-modified (:date_last_updated msg)

         ; ClinGen/ClinVar additional terms (namespaced to @vocab)
         "hasReviewStatus"    (:review_status msg)
         "inSpecies"          (:species msg)
         ;"submittedCondition"          (str iri/clinical-assertion-trait-set (:clinical_assertion_trait_set_id msg))

         }
        (-> msg
            (dissoc
              :id
              :version
              :title
              :variation_id
              :interp_description
              :trait_set_id
              :date_created
              :date_last_updated
              :interpretation_date_last_evaluated
              :review_status
              :clinical_assertion_trait_set_id
              :clinical_assertion_variations))))))

(defmethod clinvar-to-jsonld :variation_archive [msg]
  (variation-archive-to-jsonld msg))