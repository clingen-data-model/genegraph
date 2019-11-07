(ns genegraph.source.graphql.gene-dosage
  (:require [genegraph.database.query :as q]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(def feature-predicates [:sepio/has-subject :geno/is-feature-affected-by])
(def gene-label-predicates [:sepio/has-subject :geno/is-feature-affected-by :skos/preferred-label])
(def feature-predicates [:sepio/has-subject :geno/is-feature-affected-by])
(def region-label-predicates [:sepio/has-subject :geno/is-feature-affected-by :rdfs/label])
(def member-count-predicates [:sepio/has-subject :geno/has-member-count])

(defn all-dosage-propositions []
  "Returns all dosage propositions ordered by gene/region name then by haplo/triplo"
  (q/select "select ?subject where {  
      ?subject :sepio/has-subject ?dosage .
      ?dosage :geno/is-feature-affected-by ?feature .
      OPTIONAL {?feature :rdfs/label ?label} 
      OPTIONAL {?feature :skos/preferred-label ?label} }
      ORDER BY ?label ?subject
      LIMIT 20"))

(defn all-gene-feature-dosages []
  (q/select "select DISTINCT ?o where { 
    ?s :geno/is-feature-affected-by ?o . 
    ?o :skos/preferred-label ?l }"))

(defn all-region-feature-dosages []
  (q/select "select DISTINCT ?o where { 
    ?s :geno/is-feature-affected-by ?o . 
    ?o :rdfs/label ?l }"))

(defn strip-tail [iri]
  "removes x1 or x3 off of gene dosage iri Resource name"
  (subs (str iri) 0 (- (count(str iri)) 2)))

(defn dosages-for [dosage-proposition]
  (let [partial-iri (strip-tail dosage-proposition)]
    (filter #(= (strip-tail %) partial-iri) (all-dosage-propositions))))

(defn haplo [context args value]
  (first (->> (dosages-for value)
       (filter #(= 1 (q/ld1-> % member-count-predicates))))))

(defn has-haplo? [context args value]
  (not (nil? (haplo context args value))))
       
(defn triplo [context args value]
  (first (->> (dosages-for value)
    (filter #(= 3 (q/ld1-> % member-count-predicates))))))

(defn has-triplo? [context args value]
  (not (nil? (triplo context args value))))

(defn dosage-list-query [context args value]
  "Returns a list of Resources in label name order"
  (->> (all-dosage-propositions)
       (partition-by strip-tail)
       (map first)))

(defn dosage-list-query2 [context args value]
  "Returns a list of Resources in label name order"
  (->> (all-dosage-propositions)
       (partition-by strip-tail)))

(defn wg-label [context args value]
  "Gene Dosage Working Group")

(defn label [context args value]
  (str (q/ld1-> value gene-label-predicates) (q/ld1-> value region-label-predicates)))

(defn classification-description [context args value]
  (str (q/ld1-> value [[:sepio/has-subject :<] :sepio/has-object :rdfs/label]) " for dosage pathogenicity"))

(defn report-date [context args value]
  (-> (dosages-for value)
      first
      (q/ld1-> [[:sepio/has-subject :<] :sepio/qualified-contribution :sepio/activity-date])))

(defn evidence [context args value]
  (q/ld-> value [[:sepio/has-subject :<] :sepio/has-evidence-level]))

(defn gene [context args value]
  (-> (dosages-for value)
      first
      (q/ld-> [:sepio/has-subject :geno/is-feature-affected-by])
      first))

(defn score [context args value]
  (q/ld-> value []))

(defn comments [context args value]
  (q/ld-> value []))

(defn dosages [context args value]
  (q/ld-> value []))

(defn morbid [context args value]
  (q/ld-> value []))

(defn omim [context args value]
  (q/ld-> value []))

(defn haplo-percent [context args value]
  (q/ld-> value []))

(defn pli-score [context args value]
  (q/ld-> value []))

(defn phenotype [context args value]
  (q/ld-> value []))

(defn haplo-index [context args value]
  (q/ld-> value []))

(defn location-relationship [context args value]
  (q/ld-> value []))

(defn gene-name [context args value]
  value)

(defn evidence-level [dosage]
    (q/ld1-> dosage [[:sepio/has-subject :<] :sepio/has-object :rdfs/label]))

(defn description [dosage]
    (q/ld1-> dosage [[:sepio/has-subject :<] :dc/description]))

(defn haplo-evidence-level [context args value]
  (if-let [haplo (first (haplo context args value))]
    (evidence-level haplo)))

(defn haplo-description [context args value]
  (if-let [haplo (first (haplo context args value))]
    (description haplo)))

(defn triplo-evidence-level [context args value]
  (if-let [triplo (first (triplo context args value))]
    (evidence-level triplo)))

(defn triplo-description [context args value]
  (if-let [triplo (first (triplo context args value))]
    (description triplo)))

(defn genomic-feature [context args value]
  (let [gene-label (q/ld1-> value gene-label-predicates)
        feature (q/ld1-> value feature-predicates)]
    (if (nil? gene-label)
      (tag-with-type feature :region_feature)
      (tag-with-type feature :gene_feature))))

(defn gene-count [context args value]
  (count (all-gene-feature-dosages)))

(defn region-count [context args value]
  (count (all-region-feature-dosages)))

(defn total-count [context args value]
  (+ (gene-count context args value) (region-count context args value)))

;; This is the resolver function for the totals graphql query.
;; It needs to return a non-null value"
(defn totals-query [context args value]
  true)
