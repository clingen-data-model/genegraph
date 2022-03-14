(ns genegraph.annotate.replaces
  (:require [genegraph.database.query :as q]))

(defmulti add-replaces :genegraph.annotate/format)

(defmethod add-replaces :default [event]
  event)

(def find-old-gci-express-curation 
  (q/create-query 
   (str "select ?proposition where { "
        " ?report a :sepio/GeneValidityReport . "
        " ?report :dc/source :cg/GeneCurationExpress ."
        " ?report :bfo/has-part ?assertion ."
        " ?assertion a :sepio/GeneValidityEvidenceLevelAssertion . "
        " ?assertion :sepio/has-subject ?proposition ."
        " ?proposition :sepio/has-subject ?gene ."
        " ?proposition :sepio/has-qualifier ?moi ."
        " ?proposition :sepio/has-object ?disease . }")))

(defn add-replaces-for-gci [event]
  (let [subjects (:genegraph.annotate/subjects event)
        old-gci-express-curation (first
                                  (find-old-gci-express-curation
                                   {:gene (q/resource (-> subjects :gene-iris first))
                                    :disease (q/resource (-> subjects :disease-iris first))
                                    :moi (q/resource (-> subjects :moi-iris first))}))]
    (if old-gci-express-curation
      (assoc event :genegraph.annotate/replaces old-gci-express-curation)
      event)))

(defmethod add-replaces :gci-legacy [event]
  (add-replaces-for-gci event))

(defmethod add-replaces :gene-validity-raw [event]
  (add-replaces-for-gci event))
