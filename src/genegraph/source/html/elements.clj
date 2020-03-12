(ns genegraph.source.html.elements
  (:require [genegraph.database.query :as q]
            [mount.core :refer [defstate]]))

(defn- get-multis [ns]
  (->> ns ns-publics vals (filter #(= clojure.lang.MultiFn (-> % var-get type)))))

(defn- get-defined-symbols [ns]
  (->> ns get-multis (map var-get) (map methods) (map keys) (apply concat) (into #{})))


;; TODO -- Technically there is a dependency on all other namespaces here
(defstate defined-symbols :start (get-defined-symbols 'genegraph.source.html.elements))
;;(def defined-symbols (get-defined-symbols 'genegraph.source.html.elements))

(defn dispatch-by-identity-then-type
  "If the ID of the given resource is described in one of the dispatch multimethods, 
  return that, otherwise return the type of the given resource if that exists,
  :undefined otherwise. Return an arbitrary type given multiple options.

  At some point, as requirements evolve, it would be preferable to use a hierarchy of types."
  [resource]
  (if-let [c (->> resource q/to-ref defined-symbols)]
    c
    (if-let [t (->> resource :rdf/type (map q/to-ref) (filter defined-symbols) first)]
      t
      :undefined)))

(defn dispatch-by-type
  "Return a symbol representing the type of given resource, if an implementation of that type
  exists.

  As with dispatch-by-identity-then-type, an arbitrary value of type for the resource is 
  selected. It would be good to have a defined hierarchy to dispatch on in future"
  [resource]
  (if-let [t (->> resource :rdf/type (map q/to-ref) (filter defined-symbols) first)]
    t
    :undefined))


(defmulti title dispatch-by-type)

(defmethod title :default ([r] (first (concat (:skos/preferred-label r) (:rdfs/label r)))))

(defmulti link dispatch-by-type)

(defmethod link :default ([r] [:a {:href (q/path r)} (title r)]))

(defmulti page dispatch-by-identity-then-type)

(defmethod page :default 
  ([r] 
   [:body 
    [:section.section
     [:div.container
      [:h1.title (first (:rdfs/label r))]
      (map (fn [type] [:h2.subtitle (link type)]) (:rdf/type r))
      [:p (first (:iao/definition r))]]]]))

(defmulti detail-section dispatch-by-type)

(defmethod detail-section :default ([r] [:div.container
                                          [:p "resource not found"]]))

(defmulti row dispatch-by-type)

(defmethod row :default ([r] [:div.columns [:div.column "resource not found"]]))

(defmulti column dispatch-by-type)

(defmethod column :default ([r] [:div.column (q/ld1-> r [:rdf/type :rdfs/label])]))

(defmulti paragraph dispatch-by-type)

(defmethod paragraph :default ([r] [:p "resource not found: paragraph "]))

(defmulti tile dispatch-by-type)

(defmethod tile :default ([r] [:div.tile
                                [:h1.title "tile not found for resource."]
                                [:p (str r)]]))

(defmulti definition dispatch-by-type)

(defmethod definition :default ([r] (first (concat (:iao/definition r)
                                                   (:dc/description r)))))

(defmulti iri dispatch-by-identity-then-type)

(defmethod iri :default ([r] (str r)))



