(ns genegraph.source.html.elements.domain-model
  (:require [genegraph.source.html.elements :as e]
            [genegraph.database.query :as q]))

(defmethod e/title :shacl/NodeShape [shape]
  (q/ld1-> shape [:shacl/class :rdfs/label]))

(defmethod e/iri :shacl/NodeShape [shape]
  (str (q/ld1-> shape [:shacl/class])))

(defmethod e/definition :shacl/NodeShape [shape]
  (q/ld1-> shape [:shacl/class :iao/definition]))

(defn cardinality [property]
  (str (or (q/ld1-> property [:shacl/min-count]) "0")
       ".."
       (or (q/ld1-> property [:shacl/max-count]) "*")))

(defmethod e/page :shacl/NodeShape [shape]
  [:div
   [:h2 "Scope and Usage"]
   [:p (q/ld1-> shape [:shacl/class :skos/scope-note])]
   [:h2 "Attributes"]
   [:table.table.table-striped.table-bordered {:cellspacing "0" :width "100%"}
    [:thead
     [:tr
      [:th "Name"]
      [:th "Type"]
      [:th "Cardinality"]
      [:th "Description"]
      [:th "IRI"]]]
    [:tbody
     ;; TODO handle xor (union) properties
     (for [shacl-property (:shacl/property shape)]
       (let [owl-property (q/ld1-> shacl-property [:shacl/path])
             ;; TODO handle scalars defined by shacl/datatype
             property-type (first (concat (:shacl/node shacl-property)
                                          (:shacl/has-value shacl-property)
                                          (:shacl/datatype shacl-property)))]
         [:tr 
          [:td (q/ld1-> owl-property [:rdfs/label])]
          [:td (when property-type (e/link property-type))]
          [:td (cardinality shacl-property)]
          [:td (q/ld1-> owl-property [:iao/definition])]
          [:td (str owl-property)]]))]]])

(defmethod e/page :cg/DomainModel [model]
  [:div
   [:ul#myTabs.nav.nav-tabs {:role "tablist"}
    [:li.active {:role "presentation"}
     [:a#types_by_concepts-tab {:href "#types_by_concepts" :role "tab" :data-toggle "tab" :aria-controls "types_by_concepts" :aria-expanded "true"} "Categorized"]]
    [:li {:role "presentation"}
     [:a#types_alphabetical-tab {:href "#types_alphabetical" :role "tab" :data-toggle "tab" :aria-controls "types_alphabetical" :aria-expanded "false"} "Alphabetical"]]
    [:li {:role "presentation"}
     [:a#types_hierarchy-tab {:href "#types_hierarchy" :role "tab" :data-toggle "tab" :aria-controls "types_hierarchy" :aria-expanded "false"} "Hierarchical"]]]
   [:div.tab-content
    [:div#types_by_concepts.tab-pane.fade.active.in {:role "tabpanel" :aria-labelledby "types_by_concepts-tab"}
     [:br]
     [:div.col-sm-12
      [:h2 "Categorized"]]]
    [:div#types_alphabetical.tab-pane.fade.in {:role "tabpanel" :aria-labelledby "types_alphabetical-tab"}
     [:br]
     [:div.col-sm-12
      [:h2 "Alphabetic"]
      [:ul.list-unstyled
       (for [entity (get model [:cg/is-in-model :<])]
         [:li (e/link entity)])]]]
    [:div#types_hierarchy.tab-pane.fade.in {:role "tabpanel" :aria-labelledby "types_hierarchy-tab"}
     [:br]
     [:div.col-sm-12
      [:h2 "Hierarchical"]]]]])
