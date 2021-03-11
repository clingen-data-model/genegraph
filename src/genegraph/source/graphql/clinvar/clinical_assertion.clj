(ns genegraph.source.graphql.clinvar.clinical_assertion
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.clinvar.common :refer [cgterm]]
            [io.pedestal.log :as log])
  (:import (genegraph.database.query.types RDFResource)))

(defn clinical-assertion-query
  "Top level lookup for clinical assertions. Returns one record in the form of a RDFResource.
  When lacinia serializes the resource to a string it receives the iri used to construct the
  resource initially, which in our case is the assertion record iri."
  [context args value]
  (log/debug :args args :value value)
  (q/resource (:iri args)))

(defn clinical-assertion-list [context args value]
  (log/debug :args args :value value)
  (let [subject (:subject args)]
    (assert (not (nil? subject)) "Subject cannot be nil")
    (cond (= "*" subject) (q/select "SELECT ?s WHERE { ?s a :cg/VariantClinicalSignificanceAssertion . }")
          :default (q/select "SELECT ?s WHERE { ?s a :cg/VariantClinicalSignificanceAssertion ; :sepio/has-subject ?o }" {:o subject})
          )
    )
  )

(defn version [context args value]
  (log/debug :args args :value value)
  (q/ld1-> value [:dc/has-version])
  )

(defn subject [context args value]
  (log/debug :args args :value value)
  (q/ld1-> value [:sepio/has-subject]))

(defn review-status [context args value]
  (log/debug :args args :value value)

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
  (q/ld1-> value [:sepio/date-updated]))

(defn date-validated [context args value]
  (q/ld1-> value [:sepio/date-validated]))

(defn predicate [context args value]
  (q/ld1-> value [:sepio/has-predicate]))

(defn version-of [context args value]
  (q/ld1-> value [:dc/is-version-of]))

(defn contribution [context args value]
  (log/debug :args args :value value)
  (let [contribution-resource (q/ld1-> value [:sepio/qualified-contribution])]
    (log/debug :value contribution-resource)
    contribution-resource)
  )

(defn allele-origin [context args ^RDFResource value]
  (log/debug :args args :value value)
  (let [scv-iri (str value)]
    ;(log/debug "scv: " scv-iri)
    ;(q/select "SELECT ?o WHERE { ?s a :cg/VariantClinicalSignificanceAssertion; : }")
    (q/ld-> value [:cg/allele-origin])
    ))

(defn collection-method [context args value]
  (log/debug :args args :value value)
  (q/ld-> value [:cg/collection-method])
  )
