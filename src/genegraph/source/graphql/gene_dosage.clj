(ns genegraph.source.graphql.gene-dosage
  (:require [genegraph.database.query :as q]
            [genegraph.database.names :as names]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

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
      {?feature a :so/ProteinCodingGene .
       ?feature :skos/preferred-label ?label} }
      ORDER BY ?label"))

(defn all-gene-dosage-reports []
  "Selects all dosage reports for protein coding genes with a label"
  (q/select "select ?report where { 
    ?report :iao/is-about ?feature .
    ?feature a :so/ProteinCodingGene .
    ?feature :skos/preferred-label ?label }
    ORDER BY ?label"))

(defn all-non-gene-dosage-reports []
  "Selects all dosage reports for non-protein coding genes"
  (q/select "select ?report where { 
    ?report :iao/is-about ?feature .
    FILTER NOT EXISTS {?feature a :so/ProteinCodingGene .} }"))

(defn all-region-dosage-reports []
  "Selects all dosage reports for regions with a label"
  (q/select "select ?report where { 
    ?report :iao/is-about ?feature .
    ?feature a :so/SequenceFeature .
    ?feature :rdfs/label ?label }
    ORDER BY ?label"))

(defn dosage-list-query [context args value]
  "Returns a list of labelled gene and region dosage reports combined and ordered by label"
  (all-labelled-dosage-reports))

(defn wg-label [context args value]
  "Gene Dosage Working Group")

(defn haplo [context args value]
  (->> (q/ld-> value [:bfo/has-part :sepio/has-subject :sepio/has-subject])
       (filter #(= 1 (q/ld1-> % [:geno/has-member-count])))
       (first)))

(defn has-haplo? [context args value]
  (not (nil? (haplo context args value))))
       
(defn triplo [context args value]
  (->> (q/ld-> value [:bfo/has-part :sepio/has-subject :sepio/has-subject])
       (filter #(= 3 (q/ld1-> % [:geno/has-member-count])))
       (first)))
  
(defn has-triplo? [context args value]
  (not (nil? (triplo context args value))))

(defn label [context args value]
  (str (q/ld1-> value [:iao/is-about :rdfs/label])
       (q/ld1-> value [:iao/is-about :skos/preferred-label])))

(defn classification-description [context args value]
  (str (q/ld1-> value [:bfo/has-part :sepio/has-object :rdfs/label]) " for dosage pathogenicity"))

(defn report-date [context args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(defn gene? [dosage-report-resource]
  (= (str (:so/ProteinCodingGene names/local-names))
     (str (q/ld1-> dosage-report-resource [:iao/is-about :rdf/type]))))

(defn gene [context args value]
  (if (gene? value)
    (first (:iao/is-about value))
    nil))

(defn score [context args value]
  (q/ld-> value []))

(defn comments [context args value]
  (q/ld-> value []))

(defn morbid [context args value]
  (when-let [gene (gene context args value)] 
    (q/ld-> gene [[:sepio/is-about-gene :<]])))

(defn morbid-phenotypes [context args value]
  "Returns the MONDO equivalent phenotype descriptions"
  (->> (morbid context args value)
       (map #(q/ld1-> % [[:owl/equivalent-class :<] :rdfs/label]))
       (remove nil?)))

(defn omim [context args value]
  (q/ld1-> value []))

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
    (q/ld1-> dosage [[:sepio/has-subject :<] [:sepio/has-subject :<] :sepio/has-object :rdfs/label]))

(defn description [dosage]
    (q/ld1-> dosage [[:sepio/has-subject :<] :dc/description]))

(defn haplo-evidence-level [context args value]
  (when-let [haplo (haplo context args value)]
    (evidence-level haplo)))

(defn haplo-description [context args value]
  (when-let [haplo (first (haplo context args value))]
    (description haplo)))

(defn triplo-evidence-level [context args value]
  (when-let [triplo (triplo context args value)]
    (evidence-level triplo)))

(defn triplo-description [context args value]
  (if-let [triplo (first (triplo context args value))]
    (description triplo)))

(defn genomic-feature [context args value]
  (let [feature (q/ld1-> value [:iao/is-about])]
    (if (gene? value)
      (tag-with-type feature :gene_feature)
      (tag-with-type feature :region_feature))))

(defn gene-count [context args value]
  (count (all-gene-dosage-reports)))

(defn region-count [context args value]
  (count (all-region-dosage-reports)))

(defn total-count [context args value]
  (+ (gene-count context args value) (region-count context args value)))

;; This is the resolver function for the totals graphql query.
;; It needs to return a non-null value"
(defn totals-query [context args value]
  true)
