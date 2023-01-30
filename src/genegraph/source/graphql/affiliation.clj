(ns genegraph.source.graphql.affiliation
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as s]))

(def affiliation-query-with-text
  (q/create-query [:project ['affiliation]
                   (cons :bgp (concat (q/text-search-bgp 'affiliation :cg/resource 'text)
                                      curation/gene-validity-with-sort-bgp))]))

(def affiliation-query-without-text
  (q/create-query [:project ['affiliation]
                   (cons :bgp curation/gene-validity-with-sort-bgp)]))

(defresolver ^:expire-always affiliations [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (if (:text args)
                       {:text (-> args :text s/lower-case) ::q/params params}
                       {::q/params params})
        query (if (:text args)
                affiliation-query-with-text
                affiliation-query-without-text)
        result-count (future (query (assoc query-params 
                                           ::q/params
                                           {:type :count, :distinct :true})) )]
    {:agent_list (query query-params)
     :count @result-count}))

(defresolver ^:expire-always gene-validity-assertions [args value]
  (curation/gene-validity-curations-for-resolver args {:affiliation value}))

(defresolver ^:expire-always curated-genes [args value]
  (curation/validity-curated-genes-for-resolver args {:affiliation value}))

(defresolver ^:expire-always curated-diseases [args value]
  (curation/validity-curated-diseases-for-resolver args {:affiliation value}))

(defresolver affiliation-query [args value]
  (q/resource (:iri args)))
