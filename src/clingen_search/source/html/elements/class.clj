(ns clingen-search.source.html.elements.class
  (:require [clingen-search.database.query :as q]
            [clingen-search.source.html.elements :as e]))

(defmethod e/page :owl/Class 
  ([c]
   (let [title (first (:rdfs/label c))
         curations (get c [:sepio/has-object :<])]
     [:div.container
      [:section.hero-is-info
       [:div.hero-body
        [:h1.title title]]]
      [:section
       (map #(vector :p  
                     "curation"
                     ;;(first (:rdfs/label %))
                     )
            curations)]])))

(defmethod e/link :owl/Class ([c] [:a {:href (q/path c)} (first (:rdfs/label c))]))
