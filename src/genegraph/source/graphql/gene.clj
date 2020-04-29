(ns genegraph.source.graphql.gene
  (:require [genegraph.database.query :as q :refer [declare-query create-query ld-> ld1->]]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(declare-query select-gene-list)

(defn gene-query [context args value]
  (let [gene (q/resource (:iri args))]
    (if (q/is-rdf-type? gene :so/ProteinCodingGene)
       gene
       (first (filter #(q/is-rdf-type? % :so/ProteinCodingGene) (get gene [:owl/same-as :<]))))))

(def has-validity-bgp '[[validity_prop :sepio/has-subject gene]
                        [validity_prop :rdf/type :sepio/GeneValidityProposition]])

(def has-actionability-bgp '[[genetic_condition :sepio/is-about-gene gene]
                             [ac_prop :sepio/is-about-condition genetic_condition]
                             [ac_prop :rdf/type :sepio/ActionabilityReport]])

(def has-dosage-bgp '[[dosage_report :iao/is-about gene]
                      [dosage_report :rdf/type :sepio/GeneDosageReport]])

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

;; (defn curation-activities [context args value]
;;   (let [query (create-query )]))

(defn chromosome-band [context args value]
  (first (:so/chromosome-band value)))

(defn hgnc-id [context args value]
  (->> (q/ld-> value [:owl/same-as])
       (filter #(= (str (q/ld1-> % [:dc/source])) "https://www.genenames.org"))
       first
       str))

(defn curations [context args value]
  (let [actionability (q/ld-> value [[:sepio/is-about-gene :<] [:sepio/is-about-condition :<]])]
    (map #(tag-with-type % :actionability_curation)) actionability))

(defn conditions [context args value]
  (get value [:sepio/is-about-gene :<]))

;; TODO check for type (hopefully before structurally necessary)
(defn actionability-curations [context args value]
  (q/ld-> value [[:sepio/is-about-gene :<] [:sepio/is-about-condition :<]]))

;; TODO check for type (hopefully before structurally necessary)
(defn dosage-curations [context args value]
  (q/ld-> value [[:geno/is-feature-affected-by :<]
                 [:sepio/has-subject :<]
                 [:sepio/has-subject :<]]))

(defn hgnc-symbol [context args value]
  (q/ld-> value []))

(defn gene-type [context args value]
  (q/ld-> value []))

(defn locus-type [context args value]
  (q/ld-> value []))

(defn previous-symbols [context args value]
  (q/ld-> value []))

(defn alias-symbols [context args value]
  (q/ld-> value []))

(defn chromo-loc [context args value]
 (q/ld-> value []))

(defn function [context args value]
  (q/ld-> value []))

(defn coordinates [context args value]
  (q/ld-> value []))

(defn build [context args value]
  (q/ld-> value []))

(defn chromosome [context args value]
  (q/ld-> value []))

(defn start-pos [context args value]
  (q/ld-> value []))

(defn end-pos [context args value]
  (q/ld-> value []))

