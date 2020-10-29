(ns genegraph.transform.clinvar-scv
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.core :refer [add-model]]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def clinvar-variation "https://identifiers.org/clinvar:")
(def clinvar-base "https://identifiers.org/clinvar.submission:")

(defn transform-scv [scv]
  (let [content (:content scv)
        iri (str clinvar-base (:id content))
        contribution-iri (l/blank-node)]
    [[iri :rdf/type :sepio/VariantPathogenicityInterpretation]
     [iri :sepio/qualified-contribution contribution-iri]
     [contribution-iri :sepio/activity-date (:interpretation_date_last_evaluated content "")]]))

(defmethod add-model :clinvar-scv [event]
  (let [model (-> event 
                  :genegraph.sink.event/value
                  (json/parse-string true)
                  transform-scv
                  l/statements-to-model)]
    (assoc event ::q/model model)))
