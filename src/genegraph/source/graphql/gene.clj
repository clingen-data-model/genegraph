(ns genegraph.source.graphql.gene
  (:require [genegraph.database.query :as q :refer [declare-query create-query ld-> ld1->]]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
            [genegraph.source.graphql.common.curation :as curation]
            [clojure.string :as str]))

(declare-query select-gene-list)

(defn gene-query [context args value]
  (let [gene (q/resource (:iri args))]
    (if (q/is-rdf-type? gene :so/ProteinCodingGene)
       gene
       (first (filter #(q/is-rdf-type? % :so/ProteinCodingGene) (get gene [:owl/same-as :<]))))))

(def has-validity-bgp '[[validity_prop :sepio/has-subject gene]
                        [validity_prop :rdf/type :sepio/GeneValidityProposition]])

(def has-actionability-bgp '[[actionability_genetic_condition :sepio/is-about-gene gene]
                             [ac_prop :sepio/is-about-condition actionability_genetic_condition]
                             [ac_prop :rdf/type :sepio/ActionabilityReport]])

(def has-dosage-bgp '[[dosage_report :iao/is-about gene]
                      [dosage_report :rdf/type :sepio/GeneDosageReport]])


;; Internal actionability bnodes do not carry type :owl/Class
(def actionability-disease-gc-bgp
  '[[genetic_condition :sepio/is-about-gene gene]
    [ac_prop :sepio/is-about-condition disease]
    [disease :rdf/type :owl/Class]
    [ac_prop :rdf/type :sepio/ActionabilityReport]])

;; Probably need to add a little more structure to 
;; actionability curation import for this.
(def actionability-disease-bgp has-actionability-bgp)

(defn gene-list [context args value]
  (let [params (-> args (select-keys [:limit :offset]) (assoc :distinct true))
        base-bgp '[[gene :rdf/type :so/ProteinCodingGene]]
        selected-curation-type-bgp (case (:curation_type args)
                                     :GENE_VALIDITY has-validity-bgp
                                     :ACTIONABILITY has-actionability-bgp
                                     :GENE_DOSAGE has-dosage-bgp
                                     [])
        bgp (if (= :ALL (:curation_type args))
              [:union 
               (cons :bgp (concat base-bgp has-validity-bgp))
               (cons :bgp (concat base-bgp has-actionability-bgp))
               (cons :bgp (concat base-bgp has-dosage-bgp))]
              (cons :bgp
                    (concat base-bgp
                            selected-curation-type-bgp)))
        query (create-query [:project 
                             ['gene]
                             bgp])]
    
    (query {::q/params params})))

(defn curation-activities [context args value]
  (reduce (fn [acc tuple] 
            (if ((create-query (into [] (cons :bgp (first tuple))) {::q/type :ask}) {:gene value})
              (conj acc (second tuple))
              acc))
          []
          [[has-validity-bgp :GENE_VALIDITY]
           [has-actionability-bgp :ACTIONABILITY]
           [has-dosage-bgp :GENE_DOSAGE]]))

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
  (get value [:sepio/is-about-gene :<]))

;; TODO check for type (hopefully before structurally necessary)
(defn actionability-curations [context args value]
  (ld-> value [[:sepio/is-about-gene :<] [:sepio/is-about-condition :<]]))

(defn dosage-curation [context args value]
  (let [query (create-query [:project ['dosage_report] (cons :bgp has-dosage-bgp)])]
    (first (query {::q/params {:limit 1} :gene value}))))

(defn previous-symbols [context args value]
  (str/join ", " (ld-> value [:skos/hidden-label])))

(defn chromosome-band [context args value]
 (ld1-> value [:so/chromosome-band]))
