(ns genegraph.source.graphql.gene-validity
  (:require [genegraph.database.query :as q :refer [ld-> ld1-> create-query resource]]
            [genegraph.source.graphql.common.enum :as enum]
            [genegraph.source.graphql.common.curation :as curation]
            [clojure.string :as s]))

(defn report-date [context args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(defn gene-validity-list [context args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))]
    (curation/gene-validity-curations {::q/params params})))

(defn gene-validity-curations [context args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))]
    (if (:text args)
      {:curation_list (curation/gene-validity-curations-text-search
                       {:text (s/lower-case (:text args)) ::q/params params})
       :count (curation/gene-validity-curations-text-search 
               {:text (s/lower-case (:text args)) ::q/params {:type :count}})}
      {:curation_list (curation/gene-validity-curations {::q/params params})
       :count (curation/gene-validity-curations {::q/params {:type :count :distinct true}})})))


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


