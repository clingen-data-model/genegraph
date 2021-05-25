(ns genegraph.source.graphql.clinvar.aggregate_assertion
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.clinvar.common :refer [cgterm resolve-curie-namespace]]
            [genegraph.source.graphql.clinvar.variant :as variant]
            [io.pedestal.log :as log]
            [clojure.string :as s]
            [genegraph.transform.clinvar.iri :as iri])
  (:import (genegraph.database.query.types RDFResource)))

(defn aggregate-assertion-list
  "Top level lookup for aggregate assertions. Returns a list of records in the form of RDFResources.
  When lacinia serializes the resource to a string it receives the iri used to construct the
  resource initially, which in our case is the assertion record iri."
  [context args value]
  (log/debug :fn ::aggregate-assertion-list :args args :value value)

  (let [timeframe (let [tf-arg (:timeframe args "LATEST")]
                    (if (some #(= tf-arg %) ["ALL" "LATEST"])
                      tf-arg
                      (do (log/debug :msg "Parsing interval string")
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
            query-args {:id id
                        ::q/params (select-keys args [:limit :offset])}]
        (log/debug :query-args query-args)
        (q/select date-filtered-query query-args))

      (not (nil? (:subject args)))
      (let [subject (if (.startsWith (:subject args) "clinvar:")
                      (str iri/clinvar-variation (.substring (:subject args) (count "clinvar:")))
                      (resolve-curie-namespace (:subject args)))
            ;query "SELECT ?iri WHERE { ?iri :sepio/has-subject ?subject }"
            query-args {:subject (q/resource subject)
                        ::q/params (select-keys args [:limit :offset])}]
        (log/debug :query-args query-args)
        (q/select date-filtered-query query-args))

      :default (log/error :msg (str "Unknown query args: " args)))))

(defn aggregate-assertion-single
  [context args value]
  (log/debug :fn ::aggregate-assertion-single :args args :value value)
  (let [id (resolve-curie-namespace (:id args))
        query "SELECT ?iri WHERE { ?iri :dc/is-version-of ?id }"
        query-args {:id id}]
    (q/select query query-args)))

(defn version-of
  [context args value]
  (log/debug :fn ::version-of :args args :value value)
  (q/ld1-> value [:dc/is-version-of]))

(defn release-date
  [context args value]
  (log/debug :fn ::release-date :args args :value value)
  (q/ld1-> value [:cg/release-date]))

(defn subject
  [context args value]
  (log/debug :fn ::subject :args args :value value)
  (variant/variant-single nil nil (q/ld1-> value [:sepio/has-subject])))

(defn predicate
  [context args value]
  (log/debug :fn ::predicate :args args :value value)
  (q/ld1-> value [:sepio/has-predicate]))

(defn object
  [context args value]
  (log/debug :fn ::object :args args :value value)
  (q/ld1-> value [:sepio/has-object]))

(defn review-status
  [context args value]
  (log/debug :fn ::review-status :args args :value value)
  (q/ld1-> value [:cg/review-status]))

(defn version
  [context args value]
  (log/debug :fn ::version :args args :value value)
  (q/ld1-> value [:dc/has-version]))

(def aggregate-members-query-filterbased "
PREFIX dc: <http://purl.org/dc/terms/>
PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
# NOTE order matters, currently only gets the first element (column)
SELECT ?evidence_item_iri ?evidence_item_assertion_id ?evidence_item_assertion_release_date ?vcv_iri ?vcv_release_date
WHERE {
  ?vcv_iri a cg:AggregateVariantClinicalSignificanceAssertion .
  ?vcv_iri dc:isVersionOf ?idiri .
  ?vcv_iri cg:release_date ?vcv_release_date .
  # Filter to max version of each VCV
  {
    SELECT ?r_vcv_id (max(?release_date) AS ?r_vcv_max_release_date)
    WHERE {
      ?subiri a cg:AggregateVariantClinicalSignificanceAssertion ;
              dc:isVersionOf ?r_vcv_id ;
              cg:release_date ?release_date .
    }
    GROUP BY ?r_vcv_id
  }
  FILTER(?idiri = ?r_vcv_id)
  FILTER(?vcv_release_date = ?r_vcv_max_release_date)
  # Attach SCV
  ?idiri sepio:0000006 ?evidence_line_iri . # :sepio/evidence-line
  ?evidence_line_iri sepio:0000084 ?evidence_item_iri . # :sepio/evidence-item
  ?evidence_item_iri dc:isVersionOf ?evidence_item_assertion_id .
  ?evidence_item_iri cg:release_date ?evidence_item_assertion_release_date .
  # Filter to max version of each SCV
  {
    SELECT ?r_assertion_id (max(?release_date) AS ?r_assertion_max_release_date)
    WHERE {
      ?subiri a cg:VariantClinicalSignificanceAssertion ;
              dc:isVersionOf ?r_assertion_id ;
              cg:release_date ?release_date .
    }
    GROUP BY ?r_assertion_id
  }
  FILTER(?r_assertion_id = ?evidence_item_assertion_id)
  FILTER(?r_assertion_max_release_date = ?evidence_item_assertion_release_date)
}
ORDER BY ASC(?vcv_iri) ASC(?vcv_release_date)")

(def aggregate-members-query "
PREFIX dc: <http://purl.org/dc/terms/>
PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
# NOTE order matters, currently only gets the first element (column)
SELECT
  ?evidence_item_iri
  ?evidence_item_assertion_id
  ?evidence_item_assertion_release_date
  ?vcv_iri
  (?r_vcv_max_release_date as ?vcv_release_date)
WHERE {
  # Filter to max version of each VCV
  {
    SELECT ?r_vcv_id (max(?release_date) AS ?r_vcv_max_release_date)
    WHERE {
      ?subiri a cg:AggregateVariantClinicalSignificanceAssertion ;
              dc:isVersionOf ?r_vcv_id ;
              cg:release_date ?release_date .
    }
    GROUP BY ?r_vcv_id
  }
  ?vcv_iri a cg:AggregateVariantClinicalSignificanceAssertion .
  ?vcv_iri dc:isVersionOf ?r_vcv_id .
  ?vcv_iri cg:release_date ?r_vcv_max_release_date .

  # Attach SCV
  # Filter to max version of each SCV
  {
    SELECT ?evidence_item_assertion_id (max(?release_date) AS ?evidence_item_assertion_release_date)
    WHERE {
      ?subiri a cg:VariantClinicalSignificanceAssertion ;
              dc:isVersionOf ?evidence_item_assertion_id ;
              cg:release_date ?release_date .
    }
    GROUP BY ?evidence_item_assertion_id
  }
#  BIND(?r_assertion_id as ?evidence_item_assertion_id)
#  BIND(?r_assertion_max_release_date as ?evidence_item_assertion_release_date)
  ?r_vcv_id sepio:0000006 ?evidence_line_iri . # :sepio/evidence-line
  ?evidence_line_iri sepio:0000084 ?evidence_item_iri . # :sepio/evidence-item
  ?evidence_item_iri dc:isVersionOf ?evidence_item_assertion_id .
  ?evidence_item_iri cg:release_date ?evidence_item_assertion_release_date .
}
ORDER BY ASC(?vcv_iri) ASC(?vcv_release_date)")

(def aggregate-members-timeseries
  "Note that without any initial bindings, this query takes a very significant
  amount of time as it lists every single aggregate assertion and member assertion"
  "
PREFIX dc: <http://purl.org/dc/terms/>
PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
PREFIX scv: <https://identifiers.org/clinvar.submission:>
# NOTE order matters, currently only gets the first element (column)
SELECT
  ?evidence_item_iri
  ?evidence_item_assertion_id
  ?evidence_item_assertion_release_date
  ?vcv_iri
  ?r_vcv_id
  ?vcv_release_date
WHERE {
  # Take all versions of all VCVs
  ?vcv_iri a cg:AggregateVariantClinicalSignificanceAssertion .
  ?vcv_iri dc:isVersionOf ?r_vcv_id .
  ?vcv_iri cg:release_date ?vcv_release_date .

  # Attach SCVs to each
  ?r_vcv_id sepio:0000006 ?evidence_line_iri . # :sepio/evidence-line
  ?evidence_line_iri sepio:0000084 ?evidence_item_iri . # :sepio/evidence-item
  ?evidence_item_iri dc:isVersionOf ?evidence_item_assertion_id .
  ?evidence_item_iri cg:release_date ?evidence_item_assertion_release_date .
  # Filter to evidence item_assertion_release_date no later than vcv_release_date
  FILTER(?evidence_item_assertion_release_date <= ?vcv_release_date)
  # Filter to the latest evidence_item_assertion_release_date among the other
  # release dates for evidence item iris that are versions of the same assertion id
  # and are also no newer than the vcv version
  FILTER NOT EXISTS {
    ?other_evidence_item_iri_version dc:isVersionOf ?evidence_item_assertion_id .
    ?other_evidence_item_iri_version cg:release_date ?other_evidence_item_release_date .
    FILTER(?other_evidence_item_release_date <= ?vcv_release_date)
    FILTER(?other_evidence_item_release_date > ?evidence_item_assertion_release_date)
  }
}
ORDER BY ASC(?r_vcv_id) ASC(?vcv_release_date) ASC(?evidence_item_assertion_id) ")

(defn members
  "Expects value to be a RDFResource of the vcv iri."
  [context args value]
  (log/debug :fn ::members :args args :value value)
  (let [query aggregate-members-timeseries
        ; #FILTER(?vcv_iri = <http://dataexchange.clinicalgenome.org/terms/clinvar.variation_archive/VCV000000628.2020-10-10>)
        query-args {:vcv_iri value}]
    (q/select query query-args)))
