(ns clingen-search.source.html.elements.genes
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))


;;todo start here
(defmethod e/page :so/Gene [gene] 
  (let [dosage-stmts (get gene [:geno/is-feature-affected-by :<])]
    [:div.container
     [:section.hero.is-info
      [:div.hero-body
       [:h1.title (first (:skos/preferred-label gene))]
       [:h2.subtitle (first (:skos/alternative-label gene))]]]
     [:section.container
      [:h3.title.is-3 "Gene Dosage"]
      [:p "<b>hi</b>"]
      ;;[:div.columns (map e/column dosage-stmts)]
      ]]))

