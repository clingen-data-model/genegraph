(ns genegraph.source.graphql.gene-validity
  (:require [genegraph.database.query :as q :refer [ld-> ld1-> create-query resource]]
            [genegraph.source.graphql.common.enum :as enum]))

(defn report-date [context args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(def evidence-levels
  {(resource :sepio/DefinitiveEvidence) :DEFINITIVE
   (resource :sepio/LimitedEvidence) :LIMITED
   (resource :sepio/ModerateEvidence) :MODERATE
   (resource :sepio/NoEvidence) :NO_KNOWN_DISEASE_RELATIONSHIP
   (resource :sepio/RefutingEvidence) :REFUTED
   (resource :sepio/DisputingEvidence) :DISPUTED
   (resource :sepio/StrongEvidence) :STRONG})

(defn classification [context args value]
  (-> value :sepio/has-object first evidence-levels))

(defn gene [context args value]
  (ld1-> value [:sepio/has-subject :sepio/has-subject]))

(defn disease [context args value]
  (ld1-> value [:sepio/has-subject :sepio/has-object]))

(defn mode-of-inheritance [context args value]
  (enum/mode-of-inheritance  (ld1-> value [:sepio/has-subject :sepio/has-qualifier])))


