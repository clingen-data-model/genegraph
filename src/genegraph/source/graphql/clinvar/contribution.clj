(ns genegraph.source.graphql.clinvar.contribution
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [io.pedestal.log :as log]))

(defn contribution-single [context args value]
  (log/debug :fn ::contribution-single :args args :value value)
  (q/resource (:iri args)))

;(defn contribution-list [context args value]
;  (log/debug :context context :args args :value value)
;  (let [subject (:subject args)]
;    (assert (not (nil? subject)) "Subject cannot be nil")
;    (q/select "SELECT ?s WHERE { ?s a :cg/VariantClinicalSignificanceAssertion ; :sepio/has-subject ?o }" {:o subject})
;    )
;  )

(defn agent [context args value]
  (log/debug :fn ::agent :args args :value value)
  (q/ld1-> value [:sepio/has-agent]))

(defn agent-role [context args value]
  (log/debug :fn ::agent-role :args args :value value)
  (q/ld1-> value [:sepio/has-role]))

(defn activity-date [context args value]
  (log/debug :fn ::activity-date :args args :value value)
  (q/ld1-> value [:sepio/activity-date]))


