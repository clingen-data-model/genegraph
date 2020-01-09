(ns genegraph.source.html.elements.domain-model
  (:require [genegraph.source.html.elements :as e]
            [genegraph.database.query :as q]
            [cheshire.core :as json]))

(defmethod e/title :shacl/NodeShape [shape]
  (q/ld1-> shape [:shacl/class :rdfs/label]))

(defmethod e/iri :shacl/NodeShape [shape]
  (str (q/ld1-> shape [:shacl/class])))

(defmethod e/definition :shacl/NodeShape [shape]
  (q/ld1-> shape [:shacl/class :iao/definition]))

(defn cardinality [property]
  (let [min-count (q/ld1-> property [:shacl/min-count])
        max-count (q/ld1-> property [:shacl/max-count])]
    (cond
      (and (= 0 min-count) (= 1 max-count)) "zero or one"
      (and (= 1 min-count) (= 1 max-count)) "exactly one"
      (= 0 min-count) "zero or more"
      (= 1 max-count) "zero or one"
      :else "zero or more")))

(defmethod e/page :shacl/NodeShape [shape]
  [:section.section
   [:div.columns
    [:div.column.is-one-fifth
     [:div.menu
      [:p.menu-label (q/ld1-> shape [:cg/is-in-model :rdfs/label])]
      [:ul.menu-list
       (for [model-member (q/ld-> shape [:cg/is-in-model [:cg/is-in-model :<]])]
         [:li (e/link model-member)])]]]
    [:div.column 
     [:h1.title.is-3 (e/title shape)]
     [:div.columns
      [:div.column
       [:div.content.is-size-5 (e/definition shape)]
       [:div.content
        [:h1.title.is-4 "Scope and Usage"]
        (q/ld1-> shape [:shacl/class :skos/scope-note])]
       [:div.content
        [:h1.title.is-4 "Attributes"]
        (for [shacl-property (:shacl/property shape)]
          (let [owl-property (q/ld1-> shacl-property [:shacl/path])
                ;; TODO handle scalars defined by shacl/datatype
                property-type (first (concat (:shacl/node shacl-property)
                                             (:shacl/has-value shacl-property)
                                             (:shacl/datatype shacl-property)))]
            [:div.content
             [:h1.title.is-5 (q/ld1-> owl-property [:rdfs/label])]
             [:h1.subtitle.is-6.has-text-weight-light
              (when property-type (e/link property-type)) " "
              [:code (cardinality shacl-property)]]
             (q/ld1-> owl-property [:iao/definition])]))]]
      [:div.column [:pre 
                    [:code
                     (let [example (q/select
                                    "select ?x where { ?x a ?type } limit 1"
                                    {:type (q/ld1-> shape [:shacl/class])})]
                       
                       )]]]]]]])

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
