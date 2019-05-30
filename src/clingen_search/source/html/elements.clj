(ns clingen-search.source.html.elements
  (:require [clingen-search.database.query :as q]
            [mount.core :refer [defstate]]))

(defn get-multis [ns]
  (->> ns ns-publics vals (filter #(= clojure.lang.MultiFn (-> % var-get type)))))

(defn get-defined-symbols [ns]
  (->> ns get-multis (map var-get) (map methods) flatten (map keys) flatten (into #{})))

(defstate defined-symbols :start (get-defined-symbols 'clingen-search.source.html.elements))

(defn resource-dispatch [resource]
  ;; TODO, need more sophisticated type selection in case of multiple types
  (if-let [t (->> resource :rdf/type (map q/to-ref) (filter defined-symbols) first)]
    t
    :error))

(defmulti link resource-dispatch)

(defmethod link :default ([r] [:a {:href (q/path r)} (first (:rdfs/label r))]))

(defmulti page resource-dispatch)

(defmethod page :default 
  ([r] 
   [:body 
    [:section.section
     [:div.container
      [:h1.title (first (:rdfs/label r))]
      (map (fn [type] [:h2.subtitle (link type)]) (:rdf/type r))
      [:p (first (:iao/definition r))]]]]))

(defmulti detail-section resource-dispatch)

(defmethod detail-section :default ([r] [:div.container
                                          [:p "resource not found"]]))

(defmulti column resource-dispatch)

(defmethod column :default ([r] [:div.column [:p "resource not found: column"]]))

(defmulti paragraph resource-dispatch)

(defmethod paragraph :default ([r] [:p "resource not found: paragraph "]))

(defmulti tile resource-dispatch)

(defmethod tile :default ([r] [:div.tile
                                [:h1.title "tile not found for resource."]
                                [:p (str r)]]))





