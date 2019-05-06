(ns clingen-search.source.html.elements.class
  (:require [clingen-search.database.query :as q]
            [clingen-search.source.html.elements :as e]))

(defmethod e/page :shacl/NodeShape
  ([c]
   [:body
    [:section.section
     [:div.container
      [:h1.title "Node Shape"]]]]))

(defn property [p]
  [:p (first (:shacl/name p))])

(defmethod e/detail-section :shacl/NodeShape
  ([c]
   [:div.container
    (map property (:shacl/property c))]))
