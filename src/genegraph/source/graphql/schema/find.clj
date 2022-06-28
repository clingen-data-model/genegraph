(ns genegraph.source.graphql.schema.find
  (:require [genegraph.database.query :as q :refer [create-query]]
            [clojure.string :as s]))

(def assembly-and-chr->sequence
  {:GRCh37 {"chr1" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000001.10"
            "chr2" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000002.11"
            "chr3" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000003.11"
            "chr4" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000004.11"
            "chr5" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000005.9"
            "chr6" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000006.11"
            "chr7" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000007.13"
            "chr8" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000008.10"
            "chr9" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000009.11"
            "chr10" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000010.10"
            "chr11" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000011.9"
            "chr12" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000012.11"
            "chr13" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000013.10"
            "chr14" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000014.8"
            "chr15" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000015.9"
            "chr16" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000016.9"
            "chr17" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000017.10"
            "chr18" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000018.9"
            "chr19" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000019.9"
            "chr20" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000020.10"
            "chr21" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000021.8"
            "chr22" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000022.10"
            "chrX" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000023.10"
            "chrY" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000024.9"}
   :GRCh38 {"chr1" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000001.11"
            "chr2" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000002.12"
            "chr3" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000003.12"
            "chr4" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000004.12"
            "chr5" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000005.10"
            "chr6" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000006.12"
            "chr7" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000007.14"
            "chr8" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000008.11"
            "chr9" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000009.12"
            "chr10" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000010.11"
            "chr11" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000011.10"
            "chr12" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000012.12"
            "chr13" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000013.11"
            "chr14" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000014.9"
            "chr15" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000015.10"
            "chr16" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000016.10"
            "chr17" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000017.11"
            "chr18" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000018.10"
            "chr19" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000019.10"
            "chr20" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000020.11"
            "chr21" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000021.9"
            "chr22" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000022.11"
            "chrX" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000023.11"
            "chrY" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000024.10"}})

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


;; Coordinate range search is a work in progress
;; included partial progress so that we can transition to
;; different work for the time being
(def query-with-coordinate-range
  (create-query
   "select ?x where {
?x :geno/has-location ?loc .
?loc :geno/has-reference-sequence ?sequence ;
:geno/has-interval ?interval .
?interval :geno/start-position ?start_position ;
:geno/end-position ?end_position .
FILTER(?start_position > ?start)
FILTER(?end_position < ?end)
}"))

(defn coordinate-range-search [params]
  (when-let [[chromosome start end] (re-find #"(chr\w+):(\d+)-(\d+)" (:text params))]
    ))

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
