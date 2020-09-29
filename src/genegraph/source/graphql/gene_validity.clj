(ns genegraph.source.graphql.gene-validity
  (:require [genegraph.database.query :as q :refer [ld-> ld1-> create-query resource]]
            [genegraph.source.graphql.common.enum :as enum]
            [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as s]))

(defresolver gene-validity-assertion-query [args value]
  (q/resource (:iri args)))

(defresolver report-date [args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))


;; DEPRECATED
(defresolver gene-validity-list [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))]
    (curation/gene-validity-curations {::q/params params})))

(defresolver ^:expire-always gene-validity-curations [args value]
  (curation/gene-validity-curations-for-resolver args value))

;; DEPRECATED -- may not be used at all
(defresolver criteria [args value]
  nil)

(def evidence-levels
  {:sepio/DefinitiveEvidence :DEFINITIVE
   :sepio/LimitedEvidence :LIMITED
   :sepio/ModerateEvidence :MODERATE
   :sepio/NoEvidence :NO_KNOWN_DISEASE_RELATIONSHIP
   :sepio/RefutingEvidence :REFUTED
   :sepio/DisputingEvidence :DISPUTED
   :sepio/StrongEvidence :STRONG})

(defresolver classification [args value]
  (-> value :sepio/has-object first))

(defresolver gene [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-subject]))

(defresolver disease [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-object]))

(defresolver mode-of-inheritance [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-qualifier]))

(defresolver attributed-to [args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/has-agent]))

(defresolver specified-by [args value]
  (ld1-> value [:sepio/is-specified-by]))

(defn legacy-json [_ _ value]
  (ld1-> value [[:bfo/has-part :<] :bfo/has-part :cnt/chars]))
