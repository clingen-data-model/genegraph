(ns clingen-search.source.html.common
  (:require [clingen-search.database.query :as q]
            [clingen-search.source.html.elements :as e]))

(defn head
  [params]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title "ClinGen"]
   [:link {:rel "stylesheet", :media "all", :href "/css/bulma.css"}]])

(defn template
  [body params]
  [:html
   (head params)
   [:body
    (body params)]])

(defn index
  "Template to wrap every HTML request, returns structure in Hiccup syntax"
  [params]
  [:section.section
   [:div.container
    [:h1.title "What does it mean, the plague? It's life, that's all."]
    [:p.subtitle "My first website with " [:strong "Linked Data Engine"] "!"]]])

(defn- resolve-resource [curie]
  (when-let [[_ ns-prefix id] (re-find #"([A-Za-z-]*)_(.*)$" curie)]
    (q/resource ns-prefix id)))

(defn resource [params]
  (let [r (resolve-resource (get-in params [:path-params :id]))]
    [:section.section
     [:div.container
      (e/page r)
      [:p (str params)]]]))
