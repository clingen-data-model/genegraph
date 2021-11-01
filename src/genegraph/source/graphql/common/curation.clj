(ns genegraph.source.graphql.common.curation
  (:require [genegraph.database.query :as q :refer [create-query]]
            [clojure.string :as s]))

(def gene-validity-bgp
  '[[validity_proposition :sepio/has-subject gene]
    [validity_proposition :sepio/has-object disease]
    [validity_proposition :rdf/type :sepio/GeneValidityProposition]])

(def actionability-bgp
  '[[actionability_genetic_condition :sepio/is-about-gene gene]
    [ac_report :sepio/is-about-condition actionability_genetic_condition]
    [ac_report :rdf/type :sepio/ActionabilityReport]
    [actionability_genetic_condition :rdfs/sub-class-of disease]])

(def actionability-assertion-bgp
  '[[actionability_assertion :sepio/has-subject gene]
    [actionability_assertion :sepio/has-object disease]
    [actionability_assertion :rdf/type :sepio/ActionabilityAssertion]])

(def gene-dosage-bgp
  '[[dosage_report :iao/is-about gene]
    [gene :rdf/type :so/Gene]
    [dosage_report :rdf/type :sepio/GeneDosageReport]
    [dosage_report :bfo/has-part dosage_assertion]])

(def gene-dosage-disease-bgp
  (conj gene-dosage-bgp
        '[dosage_report :bfo/has-part dosage_assertion]
        '[dosage_assertion :sepio/has-subject dosage_proposition]
        '[dosage_proposition :sepio/has-object disease]))

(def curation-bgps
  [gene-validity-bgp
   actionability-bgp
   gene-dosage-disease-bgp])

(def pattern-curation-activities
  [[gene-validity-bgp :GENE_VALIDITY]
   [actionability-bgp :ACTIONABILITY]
   [gene-dosage-bgp :GENE_DOSAGE]])

(def test-resource-for-activity
  (map (fn [[pattern activity]]
         [(create-query (cons :bgp pattern) {::q/type :ask}) activity])
       pattern-curation-activities))

(defn activities [query-params]
  (reduce (fn [acc [test activity]] 
            (if (test query-params) 
              (conj acc activity)
              acc))
          #{}
          test-resource-for-activity))

(def pattern-disease-curation-activities
  [[gene-validity-bgp :GENE_VALIDITY]
   [actionability-bgp :ACTIONABILITY]
   [gene-dosage-disease-bgp :GENE_DOSAGE]])

(def test-disease-for-activity
  (map (fn [[pattern activity]]
         [(create-query (cons :bgp pattern) {::q/type :ask}) activity])
       pattern-disease-curation-activities))

(defn disease-activities [query-params]
  (reduce (fn [acc [test activity]] 
            (if (test query-params) 
              (conj acc activity)
              acc))
          #{}
          test-disease-for-activity))

(def union-of-all-curations
  (cons :union (map #(cons :bgp %) curation-bgps)))

(def actionability-curations-for-genetic-condition
  (create-query [:project ['ac_report]
                 (cons :bgp actionability-bgp)]))

(def actionability-assertions-for-genetic-condition
  (create-query [:project ['actionability_assertion]
                 (cons :bgp actionability-assertion-bgp)]))

(def gene-validity-with-sort-bgp
  (conj gene-validity-bgp
        ['validity_assertion :sepio/has-subject 'validity_proposition]
        ['gene :skos/preferred-label 'gene_label]
        ['disease :rdfs/label 'disease_label]
        ['validity_assertion :sepio/qualified-contribution 'gv_contrib]
        ['gv_contrib :bfo/realizes 'role]
        ;;['gv_contrib :sepio/activity-date 'report_date]
        ['gv_contrib :sepio/has-agent 'affiliation]
        ))

(def gene-validity-text-search-bgp
  (cons :union (map #(cons :bgp (concat (q/text-search-bgp % :cg/resource 'text)
                                        gene-validity-with-sort-bgp))
                    ['gene 'disease 'validity_assertion])))

(def gene-validity-curations-text-search
  (create-query [:project ['validity_assertion]
                 gene-validity-text-search-bgp]))

(def gene-validity-curations
  (create-query [:project ['validity_assertion]
                 (cons :bgp gene-validity-with-sort-bgp)]))

(def gene-validity-curations
  (create-query [:project ['validity_assertion]
                 ;; Adding the reference to the assertion, plus any fields likely
                 ;; to be used as sort values
                 (cons :bgp gene-validity-with-sort-bgp)]))

(def dosage-sensitivity-curations-for-genetic-condition
  (create-query [:project ['dosage_assertion]
                 (cons :bgp gene-dosage-disease-bgp)]))

(def curated-diseases-for-gene
  (create-query [:project ['disease]
                 union-of-all-curations]))

(defn curated-genetic-conditions-for-gene [query-params]
  (map #(array-map :gene (:gene query-params) :disease %) 
       (remove #(= (q/resource :mondo/Disease) %)
               (curated-diseases-for-gene query-params))))

(def curated-genes-for-disease
  (create-query [:project ['gene]
                 union-of-all-curations]))

(defn curated-genetic-conditions-for-disease [query-params]
  (map #(array-map :disease (:disease query-params) :gene %)
       (curated-genes-for-disease query-params)))

(def role-map
  {:APPROVER :sepio/ApproverRole
   :SECONDARY_CONTRIBUTOR :sepio/SecondaryContributorRole})

(defn- add-role-to-params [params]
  (case (:role params)
    :ANY (dissoc params :role)
    nil (assoc params :role (q/resource :sepio/ApproverRole))
    (assoc params :role (q/resource (role-map (:role params))))))

(defn- add-text-to-params [params]
  (if (string? (:text params))
    (assoc params :text (s/lower-case (:text params)))
    (dissoc params :text)))

(defn gene-validity-curations-for-resolver
  "Method to be called by resolvers desiring a list of gene validity curations
  with limit, sort and offset, including a total count field. Value should be a map and
  will be merged into the query parameters, limiting the result to curations that match
  the given argument"
  [args value]
  (let [params (-> args
                   (select-keys [:limit :offset :sort])
                   (assoc :distinct true))
        query-params (-> (select-keys args [:role])
                         (merge value)
                         add-role-to-params
                         add-text-to-params
                         (assoc ::q/params params))
        query (if (:text args)
                gene-validity-curations-text-search
                gene-validity-curations)
        count (future (query (assoc query-params ::q/params {:type :count})))]
    {:curation_list (query query-params)
     :count @count}))

(def validity-curated-genes
  (create-query [:project ['gene]
                 (cons :bgp gene-validity-with-sort-bgp)]))

(def validity-curated-genes-text-search
  (create-query [:project ['gene]
                 (cons :bgp
                       (concat (q/text-search-bgp 'gene :cg/resource 'text)
                               gene-validity-with-sort-bgp))]))

(defn validity-curated-genes-for-resolver
  "Method to be called by resolvers desiring a list of genes with limit, sort and offset,
  including a total count field. Value should be a map and will be merged into the query
  parameters, limiting the result to curations that match
  the given argument"
  [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (-> (if (string? (:text args))
                           {:text (s/lower-case (:text args))}
                           {})
                         (assoc ::q/params params)
                         (merge value))
        query (if (:text args)
                validity-curated-genes-text-search
                validity-curated-genes)
        count (future (query (assoc query-params ::q/params {:type :count})))]
    {:gene_list (query query-params)
     :count @count}))

(def validity-curated-diseases
  (create-query [:project ['disease]
                 (cons :bgp gene-validity-with-sort-bgp)]))

(def validity-curated-diseases-text-search
  (create-query [:project ['disease]
                 (cons :bgp
                       (concat (q/text-search-bgp 'disease :cg/resource 'text)
                               gene-validity-with-sort-bgp))]))

(defn validity-curated-diseases-for-resolver
  "Method to be called by resolvers desiring a list of diseases with limit, sort and offset,
  including a total count field. Value should be a map and will be merged into the query
  parameters, limiting the result to curations that match
  the given argument"
  [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (-> (if (string? (:text args))
                           {:text (s/lower-case (:text args))}
                           {})
                         (assoc ::q/params params)
                         (merge value))
        query (if (:text args)
                validity-curated-diseases-text-search
                validity-curated-diseases)
        count (future (query (assoc query-params ::q/params {:type :count})))]
    {:disease_list (query query-params)
     :count @count}))

(defn genes-for-resolver
  "Method to be called by resolvers desiring a list of genes with limit, sort and offset,
  including a total count field. Value should be a map and will be merged into the query
  parameters, limiting the result to curations that match
  the given argument"
  [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (-> (if (string? (:text args))
                           {:text (s/lower-case (:text args))}
                           {})
                         (assoc ::q/params params)
                         (merge value))
        gene-bgp '[[gene :rdf/type :so/Gene]
                   [gene :skos/preferred-label gene_label]]
        base-bgp (if (:text args)
                   (concat (q/text-search-bgp 'gene :cg/resource 'text) gene-bgp)
                   gene-bgp)
        selected-curation-type-bgp (case (:curation_activity args)
                                     :GENE_VALIDITY gene-validity-bgp
                                     :ACTIONABILITY actionability-bgp
                                     :GENE_DOSAGE gene-dosage-bgp
                                     [])
        bgp (if (= :ALL (:curation_activity args))
              [:union 
               (cons :bgp (concat base-bgp gene-validity-bgp))
               (cons :bgp (concat base-bgp actionability-bgp))
               (cons :bgp (concat base-bgp gene-dosage-bgp))]
              (cons :bgp
                    (concat base-bgp
                            selected-curation-type-bgp)))
        query (create-query [:project 
                             ['gene]
                             bgp])
        result-count (future (query (assoc query-params ::q/params {:type :count})))]
    {:gene_list (query query-params)
     :count @result-count}))

(defn diseases-for-resolver 
  "Method to be called by resolvers desiring a list of diseases with limit, sort and offset,
  including a total count field. Value should be a map and will be merged into the query
  parameters, limiting the result to curations that match
  the given argument"
  [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (if (:text args)
                       {:text (-> args :text s/lower-case) ::q/params params}
                       {::q/params params})
        selected-curation-type-bgp (case (:curation_activity args)
                                     :GENE_VALIDITY gene-validity-bgp
                                     :ACTIONABILITY actionability-bgp
                                     :GENE_DOSAGE gene-dosage-disease-bgp
                                     nil)
        bgp (if (= :ALL (:curation_activity args))
              [:union 
               (cons :bgp (conj gene-validity-bgp 
                                '[disease :rdfs/label disease_label]))
               (cons :bgp (conj actionability-bgp
                                '[disease :rdfs/label disease_label]))
               (cons :bgp (conj gene-dosage-disease-bgp
                                '[disease :rdfs/label disease_label]))]
              (when (some? selected-curation-type-bgp)
                (cons :bgp (conj selected-curation-type-bgp
                                 '[disease :rdfs/label disease_label]))))
        query-bgp (if (:text args) 
                    [:join (cons :bgp (q/text-search-bgp 'disease :cg/resource 'text)) bgp]
                    bgp)
        query (if (some? bgp)
                (create-query [:project 
                             ['disease]
                               query-bgp])
                ;; Consider restructuring this around a BGP when variable length
                ;; predicates are supported in the algebra, is messy as written.
                (if (:text args)
                  (create-query 
                   (str "select ?s WHERE { "
                        "?s :jena/query ( :cg/resource ?text ) . "
                        "?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                        "<http://purl.obolibrary.org/obo/MONDO_0000001> . "
                        "?s :rdfs/label ?disease_label . "
                        "FILTER (!isBlank(?s)) }"))
                  (create-query 
                   (str "select ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                        "<http://purl.obolibrary.org/obo/MONDO_0000001> . "
                        "?s :rdfs/label ?disease_label . "
                        "FILTER (!isBlank(?s)) }"))))
        result-count (future (query (assoc query-params ::q/params {:type :count})))]
    {:disease_list (query query-params)
     :count @result-count}))


(def evaluation-criteria
  (create-query 
   "select distinct ?criteria where 
{ ?criteria_type <http://www.w3.org/2000/01/rdf-schema#subClassOf>* <http://purl.obolibrary.org/obo/SEPIO_0000037> .
  ?criteria a ?criteria_type . }"))

(def classifications
  (create-query
   "select distinct ?classification where 
{ ?assertion_type <http://www.w3.org/2000/01/rdf-schema#subClassOf>* <http://purl.obolibrary.org/obo/SEPIO_0000001> .
  ?assertion a ?assertion_type .
  ?assertion :sepio/has-object ?classification . }"))
