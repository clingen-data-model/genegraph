(ns genegraph.source.graphql.clinvar.clinical_assertion
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.clinvar.common :refer [cgterm resolve-curie-namespace]]
            [genegraph.source.graphql.clinvar.variant :as variant]
            [io.pedestal.log :as log]
            [clojure.string :as s])
  (:import (genegraph.database.query.types RDFResource)))

(defn clinical-assertion-single
  "Top level lookup for clinical assertions. Returns one record in the form of a RDFResource.
  When lacinia serializes the resource to a string it receives the iri used to construct the
  resource initially, which in our case is the assertion record iri."
  [context args value]
  (log/debug :fn ::clinical-assertion-single :args args :value value)
  (q/resource (:iri args)))

(defn clinical-assertion-list [context args value]
  (log/debug :fn ::clinical-assertion-list :args args :value value)
  (assert (not (nil? (:subject args))) "Subject cannot be nil")
  (assert (not (nil? (:timeframe args))) "Timeframe cannot be nil")

  (let [subject (resolve-curie-namespace (:subject args))
        timeframe (:timeframe args)
        spql "PREFIX dc: <http://purl.org/dc/terms/>
              PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
              PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
              SELECT ?iri ?id ?subject ?release_date ?max_release_date
              WHERE {
                {
                  SELECT ?id (max(?release_date) AS ?max_release_date)
                  WHERE {
                    ?subiri a cg:VariantClinicalSignificanceAssertion ;
                            dc:isVersionOf ?id ;
                            cg:release_date ?release_date .
                  }
                  GROUP BY ?id
                }
                ?iri a cg:VariantClinicalSignificanceAssertion ;
                     dc:isVersionOf ?id ;
                     sepio:0000388 ?subject ;
                     cg:release_date ?release_date .
                {{date_filter}}
              }
              ORDER BY ASC(?id)"
        spql (cond (= "LATEST" timeframe)
                   (s/replace spql "{{date_filter}}" "FILTER(?release_date = ?max_release_date)")
                   (= "ALL" timeframe)
                   (s/replace spql "{{date_filter}}" "")
                   :default (throw (ex-info "Unknown timeframe" {:args args :value value})))
        params {:subject (if (q/resource? subject)
                           subject (q/resource subject))
                ::q/params {:limit (:limit args)
                            :offset (:offset args)}}]
    (log/debug :spql spql :params params)
    (q/select spql params))
  )

(defn version [context args value]
  (log/debug :fn ::version :args args :value value)
  (q/ld1-> value [:dc/has-version])
  )

(defn subject [context args value]
  (log/debug :fn ::subject :args args :value value)
  (variant/variant-single nil nil (q/ld1-> value [:sepio/has-subject]))
  ;(q/ld1-> value [:sepio/has-subject])
  )

(defn object [context args value]
  (log/debug :fn ::object :args args :value value)
  (q/ld1-> value [:sepio/has-object]))

(defn review-status [context args value]
  (log/debug :fn ::review-status :args args :value value)

  (let [
        assertion-iri (str value)                           ; returns the iri of the value resource
        ;review-status-resource (q/resource (cgterm "hasReviewStatus"))
        assertion-resource (q/resource assertion-iri)
        ]
    (log/debug :assertion_iri assertion-iri
              :assertion-resource assertion-resource
              ;:review-status-resource review-status-resource
              )
    (q/ld1-> assertion-resource [:cg/review-status])
    ))

(defn date-updated [context args value]
  (log/debug :fn ::date-updated :args args :value value)
  (q/ld1-> value [:sepio/date-updated]))

(defn release-date [context args value]
  (log/debug :fn ::release-date :args args :value value)
  (q/ld1-> value [:cg/release-date]))

(defn predicate [context args value]
  (log/debug :fn ::predicate :args args :value value)
  (q/ld1-> value [:sepio/has-predicate]))

(defn version-of [context args value]
  (log/debug :fn ::version-of :args args :value value)
  (q/ld1-> value [:dc/is-version-of]))

(defn contribution [context args value]
  (log/debug :fn ::contribution :args args :value value)
  (let [contribution-resource (q/ld1-> value [:sepio/qualified-contribution])]
    (log/debug :value contribution-resource)
    contribution-resource)
  )

(defn allele-origin [context args ^RDFResource value]
  (log/debug :fn ::allele-origin :args args :value value)
  (let [scv-iri (str value)]
    ;(log/debug "scv: " scv-iri)
    ;(q/select "SELECT ?o WHERE { ?s a :cg/VariantClinicalSignificanceAssertion; : }")
    (q/ld-> value [:cg/allele-origin])
    ))

(defn collection-method [context args value]
  (log/debug :fn ::collection-method :args args :value value)
  (q/ld-> value [:cg/collection-method]))

(defn classification-context [context args value]
  (log/debug :fn ::classification-context :args args :value value)
  (q/ld1-> value [:cg/classification-context]))
