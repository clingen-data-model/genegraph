(ns clingen-search.source.html.common)

(defn head
  [context]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title "ClinGen"]
   [:link {:rel "stylesheet", :media "all", :href "css/bulma.css"}]])

(defn application-template
  "Template to wrap every HTML request, returns structure in Hiccup syntax"
  [context]
  [:html
   (head context)
   [:body
    [:section.section
     [:div.container
      [:h1.title "Hello World"]
      [:p.subtitle "My first website with " [:strong "Bulma"] "!"]]]]])
