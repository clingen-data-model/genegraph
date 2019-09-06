(ns genegraph.source.html.elements.genetic-dosage
  (:require [genegraph.source.html.elements :as e]
            [genegraph.database.query :as q]))

(defmethod e/tile :geno/GeneticDosage [dosage]
  (let [proposition (first (get dosage [:sepio/has-subject :<]))]
    [:div.tile.is-vertical
     [:p.title (str "Genetic Dosage: ") (first (:geno/has-count dosage))]
     (e/tile proposition)]))


(defmethod e/column :geno/GeneticDosage [dosage]
  (let [props (get dosage [:sepio/has-subject :<])]
    [:div.column
     [:p.title.is-3 (str "genetic dosage: " (first (:geno/has-count dosage)))]
     (map e/paragraph props)]))
