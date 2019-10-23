(ns genegraph.source.graphql.gene-dosage
  (:require [genegraph.database.query :as q]))

(def gene-predicates [:sepio/has-subject :geno/is-feature-affected-by :skos/preferred-label])
(def region-predicates [:sepio/has-subject :geno/is-feature-affected-by :rdfs/label])
(def member-count-predicates [:sepio/has-subject :geno/has-member-count])

(defn all-dosage-propositions []
  (q/select "select ?s where { ?s a :sepio/DosageSensitivityProposition }"))

(defn dosages-for [name]
  (let [gene-resources (q/select (str "select ?s where { ?s :skos/preferred-label \"" name "\" }"))
        region-resources (q/select (str "select ?s where { ?s :rdfs/label \"" name "\" }"))
        resource (first (if (empty? gene-resources) region-resources gene-resources))]
    (q/ld-> resource [[:geno/is-feature-affected-by :<] [:sepio/has-subject :<]])))

(defn dosages-for-xxx [gene-or-region]
  (filter #(let [gene-name (q/ld1-> % gene-predicates)
                 region-name (q/ld1-> % region-predicates)
                 name (if (nil? gene-name) region-name gene-name)]
             (if (= name gene-or-region)
               %))
       (all-dosage-propositions)))

(defn haplo [context args value]
  (first (->> (dosages-for value)
       (filter #(= 1 (q/ld1-> % member-count-predicates))))))

(defn has-haplo? [context args value]
  (not (empty? (haplo context args value))))
       
(defn triplo [context args value]
  (first (->> (dosages-for value)
    (filter #(= 3 (q/ld1-> % member-count-predicates))))))

(defn has-triplo? [context args value]
  (not (empty? (triplo context args value))))

(defn all-genes []
  (filter #(not (nil? %))
          (reduce (fn [genes dosage]
                    (let [gene-name (q/ld-> dosage gene-predicates)]
                      (conj genes (first gene-name))))
                  #{}
                  (all-dosage-propositions))))

(defn all-regions []
  (filter #(not (nil? %))
          (reduce (fn [regions dosage]
                    (let [region-name (q/ld-> dosage region-predicates)]
                      (conj regions (first region-name))))
                  #{}
                  (all-dosage-propositions))))

(defn dosage-list-query [context args value]
  (sort (concat (all-genes) (all-regions))))

(defn gene-list-query [context args value]
  (sort (all-genes)))

(defn region-list-query [context args value]
  (sort (all-regions)))

(defn wg-label [context args value]
  "Gene Dosage Working Group")

(defn label [context args value]
  value)

(defn classification-description [context args value]
  (str (q/ld1-> value [[:sepio/has-subject :<] :sepio/has-object :rdfs/label]) " for dosage pathogenicity"))

(defn report-date [context args value]
  (-> (dosages-for value)
      first
      (q/ld1-> [[:sepio/has-subject :<] :sepio/qualified-contribution :sepio/activity-date])))

(defn evidence [context args value]
  (q/ld-> value [[:sepio/has-subject :<] :sepio/has-evidence-level]));;:sepio/has-evidence-line-with-item]))

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

(defn gene-count [context args value]
  (count (all-genes)))

(defn region-count [context args value]
  (count (all-regions)))

(defn total-count [context args value]
  (+ (gene-count context args value) (region-count context args value)))

;; This is the resolver function for the totals graphql query.
;; It needs to return a non-null value"
(defn totals-query [context args value]
  true)
