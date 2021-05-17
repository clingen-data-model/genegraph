(ns genegraph.source.graphql.common.schema
  (:require [genegraph.database.query :as q]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [genegraph.database.util :refer [tx]]))


(defn- is-list? [field]
  (and (seq? (:type field))
       (= 'list (first (:type field)))))

(defn- is-object? [field]
  (if (is-list? field)
    (keyword? (second (:type field)))
    (keyword? (:type field))))

(def type-query (q/create-query "select ?type where {?resource a / :rdfs/subClassOf * ?type}"))

;; TODO, this fn sometimes recieves nil, why?
(defn resolve-type [resource schema]
  (let [resource-types (->> (type-query {:resource resource})
                            (map q/to-ref)
                            (remove nil?)
                            (into #{}))]
    (if-let [type-mapping (->> (:type-mappings schema)
                               (filter #(resource-types (first %)))
                               first)]
      (second type-mapping)
      (:default-type-mapping schema))))

(defn- construct-resolver-from-path [field]
  (if (:path field)
    (let [resolver-fn (if (is-list? field)
                        (fn [_ _ value]
                          (q/ld-> value (:path field)))
                        (fn [_ _ value]
                          (q/ld1-> value (:path field))))]
      (assoc field :resolve resolver-fn))
    field))

(defn- attach-type-to-resolver-result [field schema]
  (if-let [resolver-fn (:resolve field)]
    (if (is-object? field)
      (if (is-list? field)
        (assoc field
               :resolve
               (fn [context args value]
                 (map #(schema/tag-with-type % (resolve-type % schema))
                      (resolver-fn context args value))))
        (assoc field
               :resolve
               (fn [context args value]
                 (let [res (resolver-fn context args value)]
                   (schema/tag-with-type res (resolve-type res schema))))))
      field)
    (if (is-object? field)
      (if (is-list? field)
        (assoc field
               :resolve
               (fn [context args value]
                 (map #(schema/tag-with-type % (resolve-type % schema))
                      (get value (:name field)))))
        (assoc field
               :resolve
               (fn [context args value]
                 (let [res (get value (:name field))]
                   (schema/tag-with-type res (resolve-type res schema))))))
      field)))

(defn- update-fields [entity schema]
  (let [fields (reduce (fn [new-fields [field-name field]]
                         (assoc new-fields
                                field-name
                                (if (:skip-type-resolution entity)
                                  (construct-resolver-from-path field)
                                  (attach-type-to-resolver-result
                                   (construct-resolver-from-path field) schema))))
                       {}
                       (:fields entity))]
    (assoc entity :fields fields)))

(defn- compose-object [entity schema]
  (let [interfaces (-> schema :interfaces (select-keys (:implements entity)) vals)
        interface-defined-fields (reduce merge (map :fields interfaces))
        updated-fields (update-fields entity schema)]
    (assoc (select-keys entity 
                        [:description :fields :implements])
           :fields
           (merge interface-defined-fields (:fields updated-fields)))))

(defn- compose-interface [entity schema]
  (select-keys (update-fields entity schema)
               [:description :fields]))

(defn- compose-query [entity schema]
  (let [query (select-keys entity [:type :args :resolve :description])]
    (if (:skip-type-resolution entity)
      query
      (attach-type-to-resolver-result query schema))))

(defn- compose-enum [entity]
  (select-keys entity [:description :values]))

(defn- add-entity-to-schema [schema entity]
  (case (:graphql-type entity)
    :interface (assoc-in schema
                           [:interfaces (:name entity)]
                           (compose-interface entity schema))
    :object (assoc-in schema
                      [:objects (:name entity)]
                      (compose-object entity schema))
    :query (assoc-in schema
                     [:queries (:name entity)]
                     (compose-query entity schema))
    :enum (assoc-in schema
                    [:enums (:name entity)]
                    (compose-enum entity))
    (merge schema entity)))

(defn schema-description [entities]
  (reduce add-entity-to-schema {} entities))

(defn schema [entities]
  (schema/compile (schema-description entities)))

(defn print-schema [schema]
  (binding [schema/*verbose-schema-printing* true]
    (clojure.pprint/pprint schema)))

(defn query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  ([schema query-str]
   (tx (lacinia/execute schema query-str nil nil)))
  ([schema query-str variables]
   (tx (lacinia/execute schema query-str variables nil))))
