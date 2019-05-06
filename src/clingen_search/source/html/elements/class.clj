(ns clingen-search.source.html.elements.class
  (:require [clingen-search.database.query :as q]
            [clingen-search.source.html.elements :as e]))

(defmethod e/page :owl/Class 
  ([c]
   (let [title (first (:rdfs/label c))
         shapes (get c [:shacl/class :<])]
     [:body
      [:section.section
       [:div.container
        [:h1.title title]
        [:p (first (:iao/definition c))]]]
      [:section.section
       [:div.container [:h1.title "shapes"]
        (map e/detail-section shapes)]]])))

(defmethod e/link :owl/Class ([c] [:a {:href (q/path c)} (first (:rdfs/label c))]))
