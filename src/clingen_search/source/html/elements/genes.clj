(ns clingen-search.source.html.elements.genes
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))

(defmethod e/page [:index :so/Gene] [c]
  (let [genes (q/select "select distinct ?x { ?x a :so/Gene . [] :geno/is-feature-affected-by ?x . } limit 10")]
    [:div.container
     [:h1.title "Genes"]
     (map e/row genes)]))

(defmethod e/row :so/Gene [gene]
  (let [knowledge-items (get gene [:geno/is-feature-affected-by :<])]
    [:div.columns
     (cons
      [:div.column.has-text-weight-bold (e/link gene) " is curated for "]
      (map e/column knowledge-items))]))


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
      [:div.columns (map e/column dosage-stmts)]]]))

