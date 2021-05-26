(ns genegraph.transform.clinvar.jsonld.gene
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-to-jsonld
                                                        variation-geno-type
                                                        genegraph-kw-to-iri]]
            [genegraph.transform.clinvar.iri :as iri]))

[::full_name
 ::id
 ::symbol
 ]
[::hgnc_id
 ]
(defn gene-to-jsonld [msg]
  (let [id-unversioned (str iri/clinvar-gene (:id msg))
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
                  :so/Gene]
         :dc/is-version-of {"@id" id-unversioned}
         :skos/preferred-label (:full_name msg)
         :sepio/qualified-contribution {:sepio/activity-date (:release_date msg)
                                        :sepio/has-role "ArchiverRole"
                                        :sepio/has-agent {"@id" (str iri/submitter "clinvar")}}
         }
        (-> msg (dissoc
                  :full_name))))))

(defmethod clinvar-to-jsonld :gene [msg]
  (gene-to-jsonld msg))
