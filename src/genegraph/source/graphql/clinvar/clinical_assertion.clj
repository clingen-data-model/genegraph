(ns genegraph.source.graphql.clinvar.clinical_assertion
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [io.pedestal.log :as log]))

(defn clinical-assertion-query [context args value]
  (log/debug :context context :args args :value value)
  (q/resource (:iri args)))

(defn version [context args value]
  (log/debug :context context :args args :value value)
  (q/ld1-> value [:dc/has-version])
  )

(defn subject [context args value]
  (log/debug :context context :args args :value value)
  (q/ld1-> value [:sepio/has-subject]))

(defn clinical-assertion-list [context args value]
  (log/debug :context context :args args :value value)
  (let [subject (:subject args)]
    (assert (not (nil? subject)) "Subject cannot be nil")
    (q/select
      "SELECT ?s WHERE { ?s a :cg/VariantClinicalSignificanceAssertion ; :sepio/has-subject ?o }"
      {:o subject})
    )
  )