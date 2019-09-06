(ns genegraph.source.html.elements.value-set
  (:require [genegraph.database.query :as q]
            [genegraph.source.html.elements :as e]))

(defmethod e/page :sepio/ValueSet
  ([r] 
   [:section.section
    [:div.container
     [:h1.title (first (:rdfs/label r))]
     [:h2.subtitle "value set"]
     [:ul
      (map #(vector :li (e/link %)) (get r [:skos/is-in-scheme :<]))]]]))
