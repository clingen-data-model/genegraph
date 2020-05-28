(ns genegraph.source.graphql.gene
  (:require [genegraph.database.query :as q :refer [declare-query create-query ld-> ld1->]]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
            [genegraph.source.graphql.common.curation :as curation]
            [clojure.string :as str]))

(defn gene-query [context args value]
  (let [gene (q/resource (:iri args))]
    (if (q/is-rdf-type? gene :so/ProteinCodingGene)
       gene
       (first (filter #(q/is-rdf-type? % :so/ProteinCodingGene) (get gene [:owl/same-as :<]))))))

(defn gene-list [context args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        gene-bgp '[[gene :rdf/type :so/ProteinCodingGene]
                   [gene :skos/preferred-label gene_label]]
        base-bgp (if (:text args)
                   (concat (q/text-search-bgp 'gene :cg/resource 'text) gene-bgp)
                   gene-bgp)
        selected-curation-type-bgp (case (:curation_type args)
                                     :GENE_VALIDITY curation/gene-validity-bgp
                                     :ACTIONABILITY curation/actionability-bgp
                                     :GENE_DOSAGE curation/gene-dosage-bgp
                                     [])
        bgp (if (= :ALL (:curation_type args))
              [:union 
               (cons :bgp (concat base-bgp curation/gene-validity-bgp))
               (cons :bgp (concat base-bgp curation/actionability-bgp))
               (cons :bgp (concat base-bgp curation/gene-dosage-bgp))]
              (cons :bgp
                    (concat base-bgp
                            selected-curation-type-bgp)))
        query (create-query [:project 
                             ['gene]
                             bgp])]
    (query {::q/params params})))

(defn genes [context args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (if (:text args)
                       {:text (-> args :text str/lower-case) ::q/params params}
                       {::q/params params})
        gene-bgp '[[gene :rdf/type :so/ProteinCodingGene]
                   [gene :skos/preferred-label gene_label]]
        base-bgp (if (:text args)
                   (concat (q/text-search-bgp 'gene :cg/resource 'text) gene-bgp)
                   gene-bgp)
        selected-curation-type-bgp (case (:curation_activity args)
                                     :GENE_VALIDITY curation/gene-validity-bgp
                                     :ACTIONABILITY curation/actionability-bgp
                                     :GENE_DOSAGE curation/gene-dosage-bgp
                                     [])
        bgp (if (= :ALL (:curation_activity args))
              [:union 
               (cons :bgp (concat base-bgp curation/gene-validity-bgp))
               (cons :bgp (concat base-bgp curation/actionability-bgp))
               (cons :bgp (concat base-bgp curation/gene-dosage-bgp))]
              (cons :bgp
                    (concat base-bgp
                            selected-curation-type-bgp)))
        query (create-query [:project 
                             ['gene]
                             bgp])]
    (println query-params)
    {:gene_list (query query-params)
     :count (query (assoc query-params ::q/params {:type :count :distinct true}))
     }))

(defn curation-activities [context args value]
  (curation/activities {:gene value}))

(defn last-curated-date [context args value]
  (let [curation-dates (concat (ld-> value [[:sepio/has-subject :<]
                                            [:sepio/has-subject :<]
                                            :sepio/qualified-contribution
                                            :sepio/activity-date])
                               (ld-> value [[:sepio/is-about-gene :<]
                                            [:sepio/is-about-condition :<]
                                            :sepio/qualified-contribution
                                            :sepio/activity-date])
                               (ld-> value [[:iao/is-about :<]
                                            :sepio/qualified-contribution
                                            :sepio/activity-date]))]
    (->> curation-dates sort last)))


(defn chromosome-band [context args value]
  (first (:so/chromosome-band value)))

(defn hgnc-id [context args value]
  (->> (q/ld-> value [:owl/same-as])
       (filter #(= (str (ld1-> % [:dc/source])) "https://www.genenames.org"))
       first
       str))

(defn curations [context args value]
  (let [actionability (ld-> value [[:sepio/is-about-gene :<] [:sepio/is-about-condition :<]])]
    (map #(tag-with-type % :actionability_curation)) actionability))

(defn conditions [context args value]
  (curation/curated-genetic-conditions-for-gene {:gene value}))

(defn dosage-curation [context args value]
  (let [query (create-query [:project ['dosage_report] (cons :bgp curation/gene-dosage-bgp)])]
    (first (query {::q/params {:limit 1} :gene value}))))

(defn previous-symbols [context args value]
  (str/join ", " (ld-> value [:skos/hidden-label])))

(defn chromosome-band [context args value]
 (ld1-> value [:so/chromosome-band]))
