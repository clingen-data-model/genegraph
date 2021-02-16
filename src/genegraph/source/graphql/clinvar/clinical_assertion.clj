(ns genegraph.source.graphql.clinvar.clinical_assertion
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [io.pedestal.log :as log]))

(defn clinical-assertion-query [context args value]
  (q/resource (:iri args)))

(defn version [context args value]
  ;(q/resource (:iri args))
  (q/ld1-> value [:dc/has-version])
  )

(defn subject [context args value]
  ;(q/resource (:iri args))
  (log/debug :value value)
  (q/ld1-> value [:sepio/has-subject])
  )