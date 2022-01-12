(ns genegraph.source.graphql.schema.find
  (:require [genegraph.database.query :as q :refer [create-query]]
            [clojure.string :as s]))

(def query-without-text-search
  (create-query
   "select ?x where {
 ?x a? | :rdfs/sub-class-of * ?type ;
 ^ :sepio/has-subject  |  ^ :sepio/has-object | ^ :sepio/has-agent  ?subject .
}"))

(def query-with-text-search
  (create-query
   "select ?x where {
      ?x :jena/query ( :cg/resource ?text ) ;
      a? | :rdfs/sub-class-of * ?type ;
      ^ :sepio/has-subject  |  ^ :sepio/has-object | ^ :sepio/has-agent  ?subject .
    }"))

(def graphql-type-to-rdf-type
  {:GENE :so/Gene
   :DISEASE :mondo/Disease
   :AFFILIATION :cg/Affiliation})

(defn args->resource [args keys]
  (->> (select-keys args keys)
       (map (fn [[k v]] [k (q/resource v)]))
       (into {})))

(defn query [_ args _]
  (let [limit-offset-sort-params (-> args
                                    (select-keys [:limit :offset :sort])
                                    (assoc :distinct true))
        query-params (-> (if (string? (:text args))
                           {:text (s/lower-case (:text args))}
                           {})
                         (merge (args->resource args [:type]))
                         (assoc ::q/params limit-offset-sort-params))
        query (if (:text args)
                query-with-text-search
                query-without-text-search)
        result-count (future (query (assoc query-params ::q/params {:type :count})))]
    
    {:results (query query-params)
     :count @result-count}))

(def types-enum
  {:name :Type
   :graphql-type :enum
   :description "Types used for filtering results in a list."
   :values (keys graphql-type-to-rdf-type)})

(def query-result
  {:name :QueryResult
   :graphql-type :object
   :description "A list of results for a given query. Includes the count of potential results, in case the list exceeds the limit."
   :fields {:results {:description "Results of the query"
                      :type '(list :Resource)
                      :resolve (fn [_ _ value] (:results value))}
            :count {:description "Total possible results in list}"
                    :type 'Int}}})

(def find-query
  {:name :find
   :graphql-type :query
   :description "Query useable to find any kind of resource in Genegraph, including genes, dieseases, affiliation groups."
   :type :QueryResult
   :skip-type-resolution true
   :args {:type {:type 'String}
          :text {:type 'String}
          :limit {:type 'Int
                  :default-value 10
                  :description "Number of records to return"}
          :offset {:type 'Int
                   :default-value 0
                   :description "Index to begin returning records from"}}
   :resolve query})
