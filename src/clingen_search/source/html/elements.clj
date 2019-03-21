(ns clingen-search.source.html.elements
  (:require [clingen-search.database.query :as q]))

(defn resource-dispatch [resource]
  ;; TODO, need more sophisticated type selection in case of multiple types
  (if-let [t (first (:rdf/type resource))]
    (q/to-ref t)
    ::default))

(defmulti page resource-dispatch)

(defmethod page ::default ([r] [:div.container
                                [:h1.title "Page not found for resource."]
                                [:p (str r)]]))

;; (defmulti summary resource-dispatch)

;; (defmethod summary ::default ([r] [:div.container
;;                                    [:p "resource not found: summary"]]))

(defmulti column resource-dispatch)

(defmethod column ::default ([r] [:div.column [:p "resource not found: column"]]))

(defmulti paragraph resource-dispatch)

(defmethod paragraph ::default ([r] [:p "resource not found: paragraph "]))

(defmulti tile resource-dispatch)

(defmethod tile ::default ([r] [:div.tile
                                [:h1.title "tile not found for resource."]
                                [:p (str r)]]))

(defmulti link resource-dispatch)

(defmethod link ::default ([r] [:p (str "Page not found for resource." (str r))]))




