(ns genegraph.source.graphql.clinvar.aggregate_assertion
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.clinvar.common :refer [cgterm resolve-curie-namespace]]
            [genegraph.source.graphql.clinvar.variant :as variant]
            [io.pedestal.log :as log]
            [clojure.string :as s])
  (:import (genegraph.database.query.types RDFResource)))

(defn aggregate-assertion-list
  "Top level lookup for aggregate assertions. Returns a list of records in the form of a RDFResource.
  When lacinia serializes the resource to a string it receives the iri used to construct the
  resource initially, which in our case is the assertion record iri."
  [context args value]
  (log/debug :args args :value value)

  (let [timeframe (let [tf-arg (:timeframe args "LATEST")]
                    (if (some #(= tf-arg %) ["ALL" "LATEST"])
                      tf-arg
                      (do (log/info :msg "Parsing interval string")
                          (log/error :msg "Not yet implemented")
                          (throw (ex-info "Intervals not supported yet, use timeframe ALL or LATEST" {:cause args})))))
        query "PREFIX dc: <http://purl.org/dc/terms/>
              PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
              PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
              SELECT ?iri ?id ?subject ?release_date ?max_release_date
              WHERE {
                {
                  SELECT ?id (max(?release_date) AS ?max_release_date)
                  WHERE {
                    ?subiri a cg:AggregateVariantClinicalSignificanceAssertion ;
                            dc:isVersionOf ?id ;
                            cg:release_date ?release_date .
                  }
                  GROUP BY ?id
                }
                ?iri dc:isVersionOf ?id ;
                     sepio:0000388 ?subject ; #:sepio/has-subject
                     cg:release_date ?release_date .
                {{date_filter}}
              }"
        latest-date-filter "FILTER(?release_date = ?max_release_date)"
        all-date-filter ""
        ; TODO interval based date filters r<edate, r>sdate, sdate<r>edate
        date-filtered-query (s/replace query "{{date_filter}}"
                                       (cond (= "LATEST" timeframe) latest-date-filter
                                             (= "ALL" timeframe) all-date-filter
                                             :default (throw (ex-info "Unknown timeframe" {:cause args}))))
        ]
    (cond
      (not (nil? (:id args)))
      (let [id (resolve-curie-namespace (:id args))
            query-args (merge {:id id}
                              (select-keys args [:limit :offset]))]
        (q/select date-filtered-query query-args))

      (not (nil? (:subject args)))
      (let [subject (resolve-curie-namespace (:subject args))
            ;query "SELECT ?iri WHERE { ?iri :sepio/has-subject ?subject }"
            query-args (merge {:subject (q/resource subject)}
                              (select-keys args [:limit :offset]))]
        (q/select date-filtered-query query-args))

      :default (log/error :msg (str "Unknown query args: " args)))))

(defn aggregate-assertion-single
  [context args value]
  (let [id (resolve-curie-namespace (:id args))
        query "SELECT ?iri WHERE { ?iri :dc/is-version-of ?id }"
        query-args {:id id}]
    (q/select query query-args)))

(defn version-of
  [context args value]
  (q/ld1-> value [:dc/is-version-of]))

(defn release-date
  [context args value]
  (q/ld1-> value [:cg/release-date]))

(defn subject
  [context args value]
  (variant/variant-single nil nil (q/ld1-> value [:sepio/has-subject])))

(defn review-status
  [context args value]
  (q/ld1-> value [:cg/review-status]))

;(defn aggregate-assertion-single
;  [context args value]
;  (let [id (resolve-curie-namespace (:id args))
;        query "SELECT ?iri WHERE { ?iri :dc/is-version-of ?id }"
;        query-args {:id id}]
;    (q/select query query-args)))
