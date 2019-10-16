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
   [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title "ClinGen Data Model"]
   ;;[:link {:rel "stylesheet", :media "all", :href "/css/bulma.css"}]
   [:link {:rel "stylesheet", :type "text/css" :href "https://cdn.datatables.net/v/bs/dt-1.10.13/fh-3.1.2/datatables.min.css"}]
   [:link {:rel "stylesheet", :type "text/css" :href "/css/bootstrap.css"}]
   [:link {:rel "stylesheet", :type "text/css" :href "/css/bootstrap-theme.css"}]
   [:link {:rel "stylesheet", :type "text/css" :href "/css/brand2.css"}]
   [:link {:rel "stylesheet", :type "text/css" :href "/css/jquery.jsonview.css"}]])

(defn template
  [body params]
  [:html
   (head params)
   [:body.documentation
    (body params)
    [:div#footer.container.background-trans.padding-top-xl
     [:div.row
      [:hr]
      [:div.col-md-col-sm-12.text-center "ClinGen"]]]
    [:script {:type "text/javascript" :src "/js/jquery.js"}]
    [:script {:type "text/javascript" :src "/js/jquery.jsonview.js"}]
    [:script {:type "text/javascript" :src "/js/bootstrap.js"}]
    [:script {:type "text/javascript" :src "https://cdn.datatables.net/v/bs/dt-1.10.13/fh-3.1.2/datatables.min.js"}]]])


(defn index
  "Template to wrap every HTML request, returns structure in Hiccup syntax"
  [params]
  [:p "Data model documentation"]
)

(defn- resolve-resource [curie]
  (when-let [[_ ns-prefix id] (re-find #"([A-Za-z-]*)_(.*)$" curie)]
    (q/resource ns-prefix id)))

(defn hero [r]
  [:div.container-fluid.hero-background
   [:div.container
    [:div.row
     [:nav.navbar.navbar-default
      [:div.navbar-header
       [:button.navbar-toggle.collapsed {:type "button" :data-toggle "collapse" :data-target "#navbar" :aria-expanded "false" :aria-controls "navbar"}
        [:span.sr-only "Toggle navigation"]
        [:span.icon-bar]
        [:span.icon-bar]
        [:span.icon-bar]]
       [:a.navbar-brand {:href "/"}
        [:img.img-responsive {:src "/img/clingen-doc-logo.png" :width "240px" :alt "ClinGen Data Model WG Documentation"}]]]
      [:div#navbar.navbar-collapse.collapse
       [:ul.nav.navbar-nav.navbar-right
        (for [model (q/select "select ?x where { ?x a :cg/DomainModel } ")]
          [:li (e/link model)])
        [:li
         [:a {:href "/"} [:i.glyphicon.glyphicon-home] "Home"]]]]]]
    [:div.row
     [:div.col-sm-12
      [:h1.header (e/title r)]
      [:blockquote
       [:p (e/definition r)]
       [:strong
        [:div (e/iri r)]]]]]]])

(defn sidebar []
  [:div.col-sm-4.col-md-3.col-lg-2
   [:div.list-group.sidenav
    [:ul.list-unstyled
     [:li.list-group-item
      [:h5 [:a {:href "/index"} "Model Overview"]]]
     [:li.list-group-item
      [:h5 [:a {:href "/index"} "Model Overview"]]]
     [:li.list-group-item
      [:h5 [:a {:href "/index"} "Model Overview"]]]
     [:li.list-group-item
      [:h5 [:a {:href "/index"} "Model Overview"]]]
     [:li.list-group-item
      [:h5 [:a {:href "/index"} "Model Overview"]]]]]])

(defn resource [params]
  (let [r (resolve-resource (get-in params [:path-params :id]))]
    [:div
     (hero r)
     [:div.container
      [:div.row
       [:div.col-sm-8.col-xs-12.col-md-9.col-lg-10
        [:article (e/page r)]]
       (sidebar)]]]))
