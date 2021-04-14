(ns genegraph.source.graphql.clinvar.gene
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.clinvar.common :refer [resolve-curie-namespace]]
            [io.pedestal.log :as log]
            [clojure.string :as s]
            [genegraph.source.graphql.clinvar.variant :as variant]))

(defn gene-resolve-unversioned
  "Assumes value is an id of a gene (unversioned)
   Or a recognizable CURIE of a gene

  args can contain the following filters:
  - timeframe: LATEST, ALL, or a date string of the latest record to return
  - limit: how many to return
  - offset: where in result set to start returning
   "
  [context args value]
  (log/debug :fn ::gene-resolve-unversioned :args args :value value)
  (assert (not (nil? value)) "value cannot be nil")
  (let [timeframe (:timeframe args "LATEST")
        query "PREFIX dc: <http://purl.org/dc/terms/>
              PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
              PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
              PREFIX so: <http://purl.obolibrary.org/obo/SO_>
              # NOTE order matters, currently only gets the first element (column)
              SELECT ?gene_iri ?gene_id ?gene_release_date
              WHERE {
                {{max_subquery}}
                {
                  SELECT ?gene_iri ?gene_id ?gene_release_date WHERE {
                    ?gene_iri a so:0000704 . # so/Gene
                    ?gene_iri a cg:ClinVarObject .
                    ?gene_iri cg:release_date ?gene_release_date .
                    ?gene_iri cg:id ?gene_id .
                  }
                }
                {{date_filter}}
              }
              ORDER BY ?gene_id ?gene_release_date"]
    (let [filter-latest "FILTER(?gene_release_date = ?max_gene_release_date)"
          filter-as-of #(format "FILTER(?gene_release_date <= \"%s\")" %)
          filter-all ""
          max-subquery "{
                  SELECT ?gene_id (MAX(?gene_release_date) AS ?max_gene_release_date) WHERE {
                    ?g a so:0000704 . # so/Gene
                    ?g a cg:ClinVarObject .
                    ?g cg:release_date ?gene_release_date .
                    ?g cg:id ?gene_id .
                  }
                  GROUP BY ?gene_id
                }"
          query (s/replace query "{{max_subquery}}" (cond (= "LATEST") max-subquery
                                                          :default ""))
          query (s/replace query "{{date_filter}}" (cond (= "LATEST" timeframe) filter-latest
                                                         (= "ALL" timeframe) filter-all
                                                         :default filter-as-of))
          _ (log/debug :query query)
          rs (q/select query {:id (if (q/resource? value) value (q/resource value))
                              ::q/params {:limit (:limit args)
                                          :offset (:offset args)}})]
      (log/debug :result rs)
      rs)))

(defn gene-list
  "args must be a map with any of the following values:
   - id: gene id (clinvar)
   - symbol: gene symbol (CURIE. ex: HGNC:RUNX1)"
  [context args value]
  (log/debug :fn ::gene-list :args args :value value)
  (let [;value (if (q/resource? value) value (q/resource (resolve-curie-namespace value)))
        ;_ (log/debug :value-processed value)
        query "PREFIX dc: <http://purl.org/dc/terms/>
              PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
              PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
              PREFIX so: <http://purl.obolibrary.org/obo/SO_>
              # NOTE order matters, currently only gets the first element (column)
              SELECT DISTINCT ?gene_iri ?gene_id ?gene_release_date ?variation_id
              WHERE {
                ?s_variant a cg:Variant .
                ?s_variant cg:gene_associations ?gene_association_iri .
                ?s_variant dc:isVersionOf ?variation_id .
                ?s_variant cg:release_date ?variant_release_date .
                ?gene_association_iri cg:gene_id ?gene_id .
                {
                  SELECT ?gene_id (MAX(?gene_release_date) AS ?max_gene_release_date) WHERE {
                    ?g a so:0000704 . # so/Gene
                    ?g a cg:ClinVarObject .
                    ?g cg:release_date ?gene_release_date .
                    ?g cg:id ?gene_id .
                  }
                  GROUP BY ?gene_id
                }
                {
                  SELECT ?gene_iri ?gene_id ?gene_release_date WHERE {
                    ?gene_iri a so:0000704 . # so/Gene
                    ?gene_iri a cg:ClinVarObject .
                    ?gene_iri cg:release_date ?gene_release_date .
                    ?gene_iri cg:id ?gene_id .
                  }
                }
                FILTER(?gene_release_date = ?max_gene_release_date)
              }
              ORDER BY ?s_variant ?gene_id"]
    (let [params (-> {::q/params {:limit (:limit args)
                                  :offset (:offset args)}}
                     ((fn [params] (if (contains? args :id)
                                     (assoc params :gene_id (java.lang.Integer/valueOf ^int (:id args)))
                                     params)))
                     ((fn [params] (if (contains? args :variation_id)
                                     (assoc params :variation_id (q/resource (:variation_id args)))
                                     params)))
                     )
          _ (log/debug :params params)
          rs (q/select query params)]
      (log/debug :result rs)
      rs)))


(defn gene-preferred-label [context args value]
  (log/debug :fn ::gene-preferred-label :args args :value value)
  (q/ld1-> value [:skos/preferred-label]))

(defn gene-symbol [context args value]
  (log/debug :fn ::gene-symbol :args args :value value)
  (q/ld1-> value [:cg/symbol]))

(defn gene-release-date [context args value]
  (log/debug :fn ::gene-release-date :args args :value value)
  (q/ld1-> value [:cg/release-date]))
