(ns genegraph.source.html.elements.class
  (:require [genegraph.database.query :as q]
            [genegraph.source.html.elements :as e]))

(defmethod e/page :shacl/NodeShape
  ([c]
   [:section.section
    [:div.container
     [:h1.title "Node Shape"]]]))

(defn value-set-descriptor [property-shape]
  (let [property (-> property-shape :shacl/path first first)
        value-set (q/ld1-> property-shape [:shacl/has-value])]
    [:div.container (e/link property)
     " has value in set "
     (e/link value-set)]))

(defn property-descriptor [property-shape]
  (let [target-shape (q/ld1-> property-shape [:shacl/node :shacl/class])
        container [:div.container (e/link (first (:shacl/path property-shape)))]]
    (if target-shape
      (conj container " target shape " (e/link target-shape))
      container)))

(defn property [property-shape]
  (if (coll? (first (:shacl/path property-shape)))
    (value-set-descriptor property-shape)
    (property-descriptor property-shape)))

(defmethod e/detail-section :shacl/NodeShape
  ([c]
   [:div.container
    [:h3.title.is-5 "properties"]
    (map property (:shacl/property c))]))
