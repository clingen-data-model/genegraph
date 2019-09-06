(ns genegraph.source.html.elements.class
  (:require [genegraph.database.query :as q]
            [genegraph.source.html.elements :as e]))

(defmethod e/page :owl/Class 
  ([c]
   (let [title (first (:rdfs/label c))
         shapes (get c [:shacl/class :<])]
     [:section.section
      [:div.container
       [:h1.title title]
       [:p (first (:iao/definition c))]]
      (map e/detail-section shapes)])))

(defmethod e/link :owl/Class ([c] [:a {:href (q/path c)} (first (:rdfs/label c))]))
