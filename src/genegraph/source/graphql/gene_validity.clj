(ns genegraph.source.graphql.gene-validity
  (:require [genegraph.database.query :as q :refer [ld-> ld1-> create-query resource]]
            [genegraph.source.graphql.common.enum :as enum]))

(defn report-date [context args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(def evidence-levels
  {:sepio/DefinitiveEvidence :DEFINITIVE
   :sepio/LimitedEvidence :LIMITED
   :sepio/ModerateEvidence :MODERATE
   :sepio/NoEvidence :NO_KNOWN_DISEASE_RELATIONSHIP
   :sepio/RefutingEvidence :REFUTED
   :sepio/DisputingEvidence :DISPUTED
   :sepio/StrongEvidence :STRONG})

(defn classification [context args value]
  (-> value :sepio/has-object first q/to-ref evidence-levels))

(defn gene [context args value]
  (ld1-> value [:sepio/has-subject :sepio/has-subject]))

(defn disease [context args value]
  (ld1-> value [:sepio/has-subject :sepio/has-object]))

(defn mode-of-inheritance [context args value]
  (-> (ld1-> value [:sepio/has-subject :sepio/has-qualifier])  
      q/to-ref
      enum/mode-of-inheritance))


