(ns genegraph.source.html.elements
  (:require [genegraph.database.query :as q]
            [mount.core :refer [defstate]]))

(defn- get-multis [ns]
  (->> ns ns-publics vals (filter #(= clojure.lang.MultiFn (-> % var-get type)))))

(defn- get-defined-symbols [ns]
  (->> ns get-multis (map var-get) (map methods) (map keys) (apply concat) (into #{})))

(defstate defined-symbols :start (get-defined-symbols 'genegraph.source.html.elements))

(defn resource-dispatch 
  "Dispatch is expected to return a namespaced symbol, used for dispatch on pages and
  page elements.
  
  Will only return a symbol that has a defined implementation, either as an index page
  for that specific resource, or on the class of the given resource."
  [resource]
  (if-let [c (->> resource q/to-ref (vector :index) defined-symbols)]
    c
    (if-let [t (->> resource :rdf/type (map q/to-ref) (filter defined-symbols) first)]
      t
      :undefined)))

(defmulti link resource-dispatch)

(defmethod link :default ([r] [:a {:href (q/path r)} 
                               (first (concat (:skos/preferred-label r) (:rdfs/label r)))]))

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

(defmulti row resource-dispatch)

(defmethod row :default ([r] [:div.columns [:div.column "resource not found"]]))

(defmulti column resource-dispatch)

(defmethod column :default ([r] [:div.column (q/ld1-> r [:rdf/type :rdfs/label])]))

(defmulti paragraph resource-dispatch)

(defmethod paragraph :default ([r] [:p "resource not found: paragraph "]))

(defmulti title resource-dispatch)

(defmethod title :default ([r] [:h1.title (link r)]))

(defmulti tile resource-dispatch)

(defmethod tile :default ([r] [:div.tile
                                [:h1.title "tile not found for resource."]
                                [:p (str r)]]))





