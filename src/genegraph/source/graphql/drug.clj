(ns genegraph.source.graphql.drug
  (:require [genegraph.database.query :as q :refer [declare-query create-query ld-> ld1->]]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.common.curation :as curation]
            [clojure.string :as str]))

(defresolver label [args value]
  (q/ld1-> value [:skos/preferred-label]))

(defresolver aliases [args value]
  (q/ld-> value [:skos/alternative-label]))

(defresolver last-curated-date [args value]
  nil)

(defresolver curation-activities [args value]
  #{})

(defresolver drug-query [args value]
  (q/resource (:iri args)))

(defresolver drugs [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        drug-bgp '[[drug :rdf/type :chebi/Drug]]
        query-params (if (:text args)
                       {:text (-> args :text str/lower-case) ::q/params params}
                       {::q/params params})
        query-bgp (cons :bgp
                        (if (:text args) 
                          (concat (q/text-search-bgp 'drug :cg/resource 'text) drug-bgp)
                          drug-bgp))
        query (create-query [:project 
                             ['drug]
                             query-bgp])]
    {:drug_list (query query-params)
     :count (query (assoc query-params ::q/params {:type :count :distinct true}))}))
