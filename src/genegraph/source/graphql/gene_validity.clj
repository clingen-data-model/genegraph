(ns genegraph.source.graphql.gene-validity
  (:require [genegraph.database.query :as q :refer [ld-> ld1-> create-query resource]]
            [genegraph.source.graphql.common.enum :as enum]
            [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as s]))

(defresolver report-date [args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(defresolver gene-validity-list [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))]
    (curation/gene-validity-curations {::q/params params})))

(defresolver gene-validity-curations [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))]
    (if (:text args)
      {:curation_list (curation/gene-validity-curations-text-search
                       {:text (s/lower-case (:text args)) ::q/params params})
       :count (curation/gene-validity-curations-text-search 
               {:text (s/lower-case (:text args)) ::q/params {:type :count}})}
      {:curation_list (curation/gene-validity-curations {::q/params params})
       :count (curation/gene-validity-curations {::q/params {:type :count :distinct true}})})))

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
  (-> value :sepio/has-object first q/to-ref evidence-levels))

(defresolver gene [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-subject]))

(defresolver disease [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-object]))

(defresolver mode-of-inheritance [args value]
  (-> (ld1-> value [:sepio/has-subject :sepio/has-qualifier])  
      q/to-ref
      enum/mode-of-inheritance))

(defresolver attributed-to [args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/has-agent]))

(defresolver criteria [args value]
  (ld1-> value [:sepio/is-specified-by]))
