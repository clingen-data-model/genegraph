(ns genegraph.source.json.common
  (:require [genegraph.database.query :as q]
            [genegraph.source.html.elements :as e]))

(defn- resolve-resource [curie]
  (when-let [[_ ns-prefix id] (re-find #"([A-Za-z-]*)_(.*)$" curie)]
    (q/resource ns-prefix id)))

(defn resource [params]
  (let [r (resolve-resource (get-in params [:path-params :id]))]
    [:section.section
     (e/page r)]))
