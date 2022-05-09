(ns genegraph.transform.clinvar.jsonld.submission
  (:require [genegraph.database.load :as l]
            [genegraph.database.names :refer [local-property-names local-class-names prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-model-to-jsonld
                                                        variation-geno-type
                                                        genegraph-kw-to-iri
                                                        vcv-review-status-to-evidence-strength-map
                                                        scv-review-status-to-evidence-strength-map]]
            [genegraph.transform.clinvar.iri :as iri]))


(defn submission-to-jsonld [msg]
  (let [id (format (str iri/submission "%s.%s")
                   (:id msg)
                   (:release_date msg))
        context {"@context" {"@vocab"  iri/cgterms
                             "clingen" iri/cgterms
                             "sepio"   "http://purl.obolibrary.org/obo/SEPIO_"
                             "clinvar" "https://www.ncbi.nlm.nih.gov/clinvar/"
                             }
                 "@id"      id}]
    (genegraph-kw-to-iri
      (merge
        context
        {"@type" (str iri/cgterms "AssertionSet")}
        msg))))

(defmethod clinvar-model-to-jsonld :submission [msg]
  (submission-to-jsonld msg))
