(ns clingen-search.source.html.elements.dosage-sensitivity-proposition
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))

(defn condition [proposition]
  (->> proposition 
       :sepio/has-object 
       (concat (q/ld-> proposition [:sepio/has-object [:owl/equivalent-class :<]]))
       (filter #(q/ld1-> % [:rdfs/label]))
       first))

(defmethod e/page :sepio/DosageSensitivityProposition [proposition]
  (if-let [dosage (first (:sepio/has-subject proposition))]
    [:div.container
     (e/title dosage)
     [:h2.subtitle 
      (e/link (first (:sepio/has-predicate proposition)))
      " "
      (e/link (condition proposition))
      ;; (str (condition proposition))
      ]
     (map e/detail-section (get proposition [:sepio/has-subject :<]))]))

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
