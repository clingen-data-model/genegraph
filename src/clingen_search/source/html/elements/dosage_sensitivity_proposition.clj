(ns clingen-search.source.html.elements.dosage-sensitivity-proposition
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))

(defmethod e/paragraph :sepio/DosageSensitivityProposition [proposition]
  (let [disease (q/ld1-> proposition [:sepio/has-object [:skos/has-exact-match :<]])]
    [:p
     [:p (q/ld1-> proposition [:sepio/has-predicate :rdfs/label])]
     [:p.title (e/link disease)]]))

(defmethod e/tile :sepio/DosageSensitivityProposition [proposition]
  (let [disease (q/ld1-> proposition [:sepio/has-object [:skos/has-exact-match :<]])]
    [:div.tile
     [:p (q/ld1-> proposition [:sepio/has-predicate :rdfs/label])]
     [:p.title (e/link disease)]]))
