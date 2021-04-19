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
