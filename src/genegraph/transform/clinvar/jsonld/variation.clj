(ns genegraph.transform.clinvar.jsonld.variation
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-to-jsonld
                                                        variation-geno-type
                                                        genegraph-kw-to-iri]]
            [genegraph.transform.clinvar.iri :as iri]
            [taoensso.timbre :as log]))

[::id
 ::name
 ::protein_change
 ::subclass_type                                            ;For SimpleAllele, no child_ids or descendant_ids, for Genotype/Haplotype, must have child+descendant
 ::variation_type
 ]
[::allele_id                                                ; TODO 0.0864% null (this is okay)
 ::child_ids
 ::content
 ::descendant_ids
 ::num_chromosomes
 ::num_copies
 ]
(defn variation-to-jsonld [msg]
  (let [id-unversioned (str iri/clinvar-variation (:id msg))
        id (str id-unversioned "." (:release_date msg))
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
                  (str iri/cgterms "Variant")]
         :dc/is-version-of {"@id" id-unversioned}
         :dc/has-version (:version msg)

         :sepio/date-created (:date_created msg)
         :sepio/date-modified (:date_last_updated msg)

         :sepio/qualified-contribution {:sepio/activity-date (:release_date msg)
                                        :sepio/has-role "ArchiverRole"
                                        :sepio/has-agent {"@id" (str iri/submitter "clinvar")}}
         }
        (-> msg (dissoc :id
                        :version
                        :date_created
                        :date_last_updated
                        ))))))

(defmethod clinvar-to-jsonld :variation [msg]
  (variation-to-jsonld msg))
