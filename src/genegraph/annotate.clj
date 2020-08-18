(ns genegraph.annotate
  (:require [genegraph.database.query :as q]
            [genegraph.database.validation :as validate]
            [genegraph.database.util :as util :refer [tx]]
            [genegraph.env :as env]
            [genegraph.transform.core :as transform]
            [genegraph.transform.gci-legacy :as gci-legacy]
            [genegraph.transform.actionability :as aci]
            [genegraph.transform.gci-neo4j :as gci-neo4j]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [io.pedestal.log :as log]))

(def formats (-> "formats.edn" io/resource slurp edn/read-string))
(def shapes (-> "shapes.edn" io/resource slurp edn/read-string))

(defn add-metadata [event]
  (merge event (get formats (::format event))))

(defn add-iri [event]
  (let [iri (-> (q/select "select ?x where {?x a ?type}"
                          {:type (::root-type event)}
                          (::q/model event))
                first
                str)]
    (assoc event ::iri iri)))

(defn add-gene
  "Annotate event with topic genes."
  [event])

(defn add-model 
  "Annotate event with model derived from its value"
  [event]
  (transform/add-model event))

(defn add-validation
  "Annotate the event with the result of any configured Shacl validation"
  [event]
  (if (true? (Boolean/valueOf env/validate-events))
    (when-let [shape-doc-def (-> event ::root-type shapes)]
      (tx
       (let [shape-model (q/get-named-graph (:graph-name shape-doc-def))
             data-model (::q/model event)
             validation-result (validate/validate data-model shape-model)
             did-validate (validate/did-validate? validation-result)
             turtle (println (q/to-turtle validation-result))
             iri (::iri event)
             root-type (::root-type event)]
         (log/info :fn :add-validation :root-type root-type :iri iri :did-validate? did-validate :report turtle)
         (assoc event ::validation validation-result))))
    event))

(defn add-subjects-to-event
  "Perform the actual event annotation, returnng the event to th caller"
  [event genes diseases]
  (let [gene-iris (mapv #(str %) genes)
         disease-iris (mapv #(str %) diseases)]
    (assoc event ::subjects {:gene-iris gene-iris :disease-iris disease-iris})))

(defmulti add-subjects ::root-type)

(defmethod add-subjects :sepio/GeneDosageReport [event]
  "Annotate a gene dosage event with the gene iri to which the event pertains"
  (if-let [genes (q/select "select ?o where { ?s :iao/is-about ?o }" {} (::q/model event))]
    (add-subjects-to-event event genes [])
    event))

(defmethod add-subjects :sepio/ActionabilityReport [event]
  "Annotate an actionability report event with genes and diseases to which the event pertains"
  (if-let [act-condition (first (q/select "select ?s where { ?s a :cg/ActionabilityGeneticCondition }" {}
                                          (::q/model event)))]
    (let [genes (q/ld-> act-condition [:sepio/is-about-gene])
          diseases (q/ld-> act-condition [:rdfs/sub-class-of])]
      (add-subjects-to-event event genes diseases))
    event))

(defmethod add-subjects :sepio/GeneValidityReport [event]
  "Annotate a Gene Validity report event with the gene iri and the disease iri to which the event pertains"
  (if-let [gv-prop (first (q/select "select ?s where { ?s a :sepio/GeneValidityProposition }" {}
                                    (::q/model event)))]
    (let [genes (q/ld-> gv-prop [:sepio/has-subject])
          diseases (q/ld-> gv-prop [:sepio/has-object])]
      (add-subjects-to-event event genes diseases))
    event))

(defmethod add-subjects :default [event] event)
  


