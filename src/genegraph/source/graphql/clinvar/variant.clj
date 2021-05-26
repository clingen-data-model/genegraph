(ns genegraph.source.graphql.clinvar.variant
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.clinvar.common :refer [resolve-curie-namespace]]
            [io.pedestal.log :as log]))

(defn variant-single
  "Retrieves a single variant resource based on args. If the value is a string, it converts it to an RDFResource.
  value:
  - RDFResource of the :cg/Variant id (:dc/is-version-of)
  - String of the :cg/Variant id (:dc/is-version-of)

  TODO if value is empty, use (:id args) instead. This means it is a direct Variant query, not
  a variant query triggered by recursing to a member from an object containing a Variant.
  ;args:
  ;- :id identifier for the variant (no version, will retrieve latest)
  TODO
  - :before iso8601 datetime to set cutoff to (will retrieve latest at or before this datetime)"
  [context args value]
  (log/debug :fn ::variant-single :args args :value value)
  (let [value (if (q/resource? value)
                value
                (q/resource (resolve-curie-namespace value)))
        query "PREFIX dc: <http://purl.org/dc/terms/>
              PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
              SELECT ?iri ?id
              WHERE {
                {
                  SELECT ?id (max(?release_date) AS ?max_release_date)
                  WHERE {
                    ?subiri a cg:Variant ;
                            dc:isVersionOf ?id ;
                            cg:release_date ?release_date .
                  }
                  GROUP BY ?id
                }
                ?iri a cg:Variant ;
                     dc:isVersionOf ?id ;
                     cg:release_date ?release_date .
                FILTER(?release_date = ?max_release_date)

              }"]
    (let [rs (q/select query {:id (if (q/resource? value)
                                    value
                                    (q/resource value))})]
      (if (< 1 (count rs))
        (throw (ex-info "Single variant query returned more than 1 response" {:args args :value value :rs rs})))
      (log/debug :result (first rs))
      (first rs))))

(defn variant-name [context args value]
  (log/debug :fn ::variant-name :args args :value value)
  (q/ld1-> value [:cg/name]))

(defn variant-release-date [context args value]
  (log/debug :fn ::variant-release-date :args args :value value)
  (q/ld1-> value [:cg/release-date]))

(defn variant-id [context args value]
  (log/debug :fn ::variant-id :args args :value value)
  (q/ld1-> value [:dc/is-version-of]))

(defn variant-genes [context args value]
  "Expects value to be passed as an IRI to a variant"
  (log/debug :fn ::variant-genes :args args :value value)
  (let [gene-iri-query (str "PREFIX dc: <http://purl.org/dc/terms/>
                            PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
                            PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
                            PREFIX so: <http://purl.obolibrary.org/obo/SO_>
                            # NOTE order matters, currently only gets the first element (column)
                            SELECT ?gene_iri ?gene_id ?gene_release_date ?s
                            WHERE {
                              ?s a cg:Variant .
                              ?s cg:gene_associations ?gene_association_iri .
                              ?s cg:release_date ?variant_release_date .
                              ?gene_association_iri cg:gene_id ?gene_id .
                              {
                                SELECT ?gene_id (MAX(?gene_release_date) AS ?max_gene_release_date) WHERE {
                                  ?g a so:0000704 . # so/Gene
                                  ?g a cg:ClinVarObject .
                                  ?g cg:release_date ?gene_release_date .
                                  ?g cg:id ?gene_id .
                                }
                                GROUP BY ?gene_id
                              }
                              {
                                SELECT ?gene_iri ?gene_id ?gene_release_date WHERE {
                                  ?gene_iri a so:0000704 . # so/Gene
                                  ?gene_iri a cg:ClinVarObject .
                                  ?gene_iri cg:release_date ?gene_release_date .
                                  ?gene_iri cg:id ?gene_id .
                                }
                              }
                              FILTER(?gene_release_date = ?max_gene_release_date)
                            }
                            ORDER BY ?s ?gene_id")
        variant-iri (if (q/resource? value) value (q/resource value))
        rs (q/select gene-iri-query {:s variant-iri})]
    rs
    ))
