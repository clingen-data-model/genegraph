(ns genegraph.source.html.common
  (:require [genegraph.database.query :as q]
            [genegraph.source.html.elements :as e]
            [genegraph.source.html.elements.class]
            [genegraph.source.html.elements.dosage-sensitivity-proposition]
            [genegraph.source.html.elements.evidence-level-assertion]
            [genegraph.source.html.elements.genes]
            [genegraph.source.html.elements.genetic-dosage]
            [genegraph.source.html.elements.node-shape]
            [genegraph.source.html.elements.predicate]
            [genegraph.source.html.elements.value-set]
            [genegraph.source.html.elements.functional-copy-number-complement]))

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
    [:h1.title "ClinGen Search."]]])

(defn- resolve-resource [curie]
  (when-let [[_ ns-prefix id] (re-find #"([A-Za-z-]*)_(.*)$" curie)]
    (q/resource ns-prefix id)))

(defn resource [params]
  (let [r (resolve-resource (get-in params [:path-params :id]))]
    [:section.section
     (e/page r)]))
