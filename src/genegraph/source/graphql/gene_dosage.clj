(ns genegraph.source.graphql.gene-dosage
  (:require [clojure.string :as string]
            [genegraph.database.names :as names]
            [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
            [genegraph.database.instance :refer [db]])
  (:import  [org.apache.jena.query ReadWrite QueryFactory QueryExecutionFactory]
            [org.apache.jena.sparql.util QueryExecUtils]))

(defn all-dosage-reports []
  (q/select "select ?report where {  
      ?report a :sepio/GeneDosageReport }"))

(defn all-labelled-dosage-reports []
  "Returns all dosage reports with a gene name or a region label
   ordered by gene name/region label"
  (q/select "select ?report where {  
      ?report a :sepio/GeneDosageReport .
      ?report :iao/is-about ?feature
      {?feature a :so/SequenceFeature .
      ?feature :rdfs/label ?label}
      UNION
      {?feature a :so/Gene .
       ?feature :skos/preferred-label ?label} }
      ORDER BY ?label"))

(defn all-gene-dosage-reports []
  "Selects all dosage reports for protein coding genes with a label"
  (q/select "select ?report where { 
    ?report a :sepio/GeneDosageReport .
    ?report :iao/is-about ?feature .
    ?feature a :so/Gene .
    ?feature :skos/preferred-label ?label }
    ORDER BY ?label"))

(defn all-non-gene-dosage-reports []
  "Selects all dosage reports for non-protein coding genes"
  (q/select "select ?report where {
    ?report a :sepio/GeneDosageReport .
    ?report :iao/is-about ?feature .
    FILTER NOT EXISTS {?feature a :so/Gene .} }"))

(defn all-region-dosage-reports []
  "Selects all dosage reports for regions with a label"
  (q/select "select ?report where { 
    ?report a :sepio/GeneDosageReport .
    ?report :iao/is-about ?feature .
    ?feature a :so/SequenceFeature .
    ?feature :rdfs/label ?label }
    ORDER BY ?label"))

(defn dedupe-resources [query-results]
  "Takes a seq of Resources returned from a select query 
   and de-duplicates based on the uniqueness of the resource iri
   and returns as unique list of resources"
  (map #(->> % q/resource)
       ;; remove duplicates from a resource returning select query
       ;; by transforming resources to their iri and
       ;; then mapping all unique iri's back to resources
       (distinct (reduce (fn [v resource]
                           (->> resource
                                .toString
                                (conj v)))
                         []
                         query-results))))

(defn gene-filter [genes]
  ;; cg-genes is a property-list defined in resources/genegraph-assembly.ttl
  ;; its a search across a number of triple properties and will return duplicates.
  (let [genes-or-lower (->> (map string/lower-case genes)
                         (string/join " OR "))
        gq (str "PREFIX text: <http://jena.apache.org/text#> 
             PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
             SELECT ?report WHERE { 
             ?feature text:query ( cg:genes '( " genes-or-lower " )' ) .
             ?feature a :so/Gene .
             ?report :iao/is-about ?feature .
             ?report a :sepio/GeneDosageReport }")]
    ;; (q/select gq {:genes genes-or-lower})))
    (q/select gq)))

(defn region-filter [regions]
  (let [regions-or-lower (->> (map string/lower-case regions)
                         (string/join " OR "))
        rq (str "PREFIX text: <http://jena.apache.org/text#> 
             SELECT ?report WHERE { 
             ?feature text:query ( :rdfs/label '( " regions-or-lower " )' ) .
             ?feature a :so/SequenceFeature .
             ?report :iao/is-about ?feature .
             ?report a :sepio/GeneDosageReport }")]
    ;; (q/select rq {:regions regions-or-lower})))
    (q/select rq)))

(defn diseases-filter [diseases]
  (let [diseases-or-lower (->> (map string/lower-case diseases)
                         (string/join " OR "))
        dq (str "PREFIX text: <http://jena.apache.org/text#> 
             PREFIX cg: <http://dataexchange.clinicalgenome.org/terms/>
             SELECT ?report WHERE { 
             ?condition text:query ( cg:diseases '( " diseases-or-lower " )' ) .
             ?condition a :sepio/GeneticCondition .
             ?condition :owl/equivalent-class ?equivclass .
             ?equivclass :sepio/is-about-gene ?gene .
             ?report :iao/is-about ?gene .
             ?report a :sepio/GeneDosageReport }")]
    ;; (q/select dq {:diseases diseases-or-lower})))
    (q/select dq)))

(defn filtered-dosage-reports [filters]
  (->> (reduce (fn [vec [k v]]
            (cond
              (= :regions k) (conj vec (region-filter v))
              (= :genes k) (conj vec (gene-filter v))
              (and (= :protein_coding k)
                   (= 1 (count (keys filters)))) (conj vec (all-gene-dosage-reports))
              (= :diseases k) (conj vec (diseases-filter v))))
          []
          filters)
       flatten))

(defresolver dosage-list-query [args value]
  "Returns a list of labelled gene and region dosage reports combined and ordered by label"
  (if (:filters args)
    (filtered-dosage-reports (:filters args))
    (all-labelled-dosage-reports)))

(defresolver gene-dosage-query [args value]
  (q/resource (:iri args)))

(defresolver wg-label [args value]
  "Gene Dosage Working Group")

(defresolver haplo [args value]
  (->> (q/ld-> value [:bfo/has-part])
       (filter #(= 1 (q/ld1-> % [:sepio/has-subject :sepio/has-subject :geno/has-member-count])))
       (first)))

(defresolver has-haplo? [args value]
  (not (nil? (haplo args value))))
       
(defresolver triplo [args value]
  (->> (q/ld-> value [:bfo/has-part])
       (filter #(= 3 (q/ld1-> % [ :sepio/has-subject :sepio/has-subject :geno/has-member-count])))
       (first)))
  
(defresolver has-triplo? [args value]
  (not (nil? (triplo args value))))

(defresolver label [args value]
  (str (q/ld1-> value [:iao/is-about :rdfs/label])
       (q/ld1-> value [:iao/is-about :skos/preferred-label])))
       

(defresolver classification-description [args value]
  (str (q/ld1-> value [:bfo/has-part :sepio/has-object :rdfs/label]) " for dosage pathogenicity"))

(defresolver report-date [args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(defn gene? [dosage-report-resource]
  (= (str (:so/Gene names/local-names))
     (str (q/ld1-> dosage-report-resource [:iao/is-about :rdf/type]))))

(defresolver gene [args value]
  (if (gene? value)
    (first (:iao/is-about value))
    nil))

(defresolver morbid [args value]
  (when-let [gene (gene args value)] 
    (q/ld-> gene [[:sepio/is-about-gene :<]])))

(defresolver morbid-phenotypes [args value]
  "Returns the MONDO equivalent phenotype descriptions"
  (->> (morbid args value)
       (map #(q/ld1-> % [[:owl/equivalent-class :<] :rdfs/label]))
       (remove nil?)))

(defresolver omim [args value]
  (q/ld1-> value []))

(defresolver pli-score [args value]
  (if-let [triplo (first (q/select "select ?s where { ?s :iao/is-about ?gene ; a :cg/TriplosensitivityScore }"
                {:gene (first (:iao/is-about value))}))]
    (q/ld1-> triplo [:sepio/confidence-score])
    nil))
   
(defresolver haplo-index [args value]
  (if-let [haplo (first (q/select "select ?s where { ?s :iao/is-about ?gene ; a :cg/HaploinsufficiencyScore }"
                {:gene (first (:iao/is-about value))}))]
    (q/ld1-> haplo [:sepio/confidence-score])
    nil))

(defresolver location-relationship [args value]
  (q/ld-> value []))

(defn evidence-level [dosage]
    (q/ld1-> dosage [[:sepio/has-subject :<] [:sepio/has-subject :<] :sepio/has-object :rdfs/label]))

(defn description [dosage]
    (q/ld1-> dosage [[:sepio/has-subject :<] :dc/description]))

(defresolver haplo-evidence-level [args value]
  (when-let [haplo (haplo args value)]
    (evidence-level haplo)))

(defresolver haplo-description [args value]
  (when-let [haplo (first (haplo args value))]
    (description haplo)))

(defresolver triplo-evidence-level [args value]
  (when-let [triplo (triplo args value)]
    (evidence-level triplo)))

(defresolver triplo-description [args value]
  (if-let [triplo (first (triplo args value))]
    (description triplo)))

(defresolver genomic-feature [args value]
  (let [feature (q/ld1-> value [:iao/is-about])]
    (if (gene? value)
      (tag-with-type feature :GeneFeature)
      (tag-with-type feature :RegionFeature))))

(defresolver gene-count [args value]
  (count (all-gene-dosage-reports)))

(defresolver region-count [args value]
  (count (all-region-dosage-reports)))

(defresolver total-count [args value]
  (+ (gene-count args value) (region-count args value)))

;; This is the resolver function for the totals graphql query.
;; It needs to return a non-null value"
(defresolver totals-query [args value]
  true)
