(ns clingen-search.source.html.elements.genes
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))

(defmethod e/page :so/Gene [gene] [:h1.title (str "Label: " (:skos/preferred-label gene))])

