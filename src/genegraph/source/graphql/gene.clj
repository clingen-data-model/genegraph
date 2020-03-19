(ns genegraph.source.graphql.gene
  (:require [genegraph.database.query :as q]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(defn gene-query [context args value]
  (let [gene (q/resource (:iri args))]
    (println gene)
    (if (q/is-rdf-type? gene :so/ProteinCodingGene)
       gene
       (first (filter #(q/is-rdf-type? % :so/ProteinCodingGene) (get gene [:owl/same-as :<]))))))

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


(defn validity-curations [context args value]
  (q/select "select ?report where { ?report a :sepio/GeneValidityReport .
 ?report :bfo/has-part ?assertion .
 ?assertion :sepio/has-subject ?proposition .
 ?proposition :sepio/has-subject ?gene }"
            {:gene value}))
