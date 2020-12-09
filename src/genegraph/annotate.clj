(ns genegraph.annotate
  (:require [genegraph.annotate.action :as action]
            [genegraph.annotate.replaces :as replaces]
            [genegraph.database.query :as q]
            [genegraph.database.validation :as validate]
            [genegraph.database.util :as util :refer [tx]]
            [genegraph.env :as env]
            [genegraph.interceptor :as intercept :refer [interceptor-enter-def]]
            [genegraph.transform.core :as transform]
            [genegraph.transform.gci-legacy :as gci-legacy]
            [genegraph.transform.actionability :as aci]
            [genegraph.transform.gci-neo4j :as gci-neo4j]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor.chain :as ic :refer [terminate]]))

(def formats (-> "formats.edn" io/resource slurp edn/read-string))
(def shapes (-> "shapes.edn" io/resource slurp edn/read-string))

(defn add-action [event]
  (action/add-action event))

(def add-action-interceptor
  "Interceptor for annotating an event with a publish or unpublish action."
  {:name ::add-action
   :enter add-action})
  
(defn add-metadata [event]
  (log/debug :fn :add-metadata :event event :msg :received-event)
  (merge event (get formats (::format event))))

(def add-metadata-interceptor
  "Interceptor adding metadata annotation to stream events"
  (interceptor-enter-def ::add-metadata add-metadata))

(defn add-iri [event]
  (log/debug :fn :add-iri :event event :msg :received-event)
  (if (::graph-name event)
    (let [iri (-> (q/select "select ?x where {?x a ?type}"
                            {:type (::graph-name event)}
                            (::q/model event))
                  first
                  str)]
      (assoc event ::iri iri))
    event))

(def add-iri-interceptor
  "Interceptor adding iri annotation to stream events"
  (interceptor-enter-def ::add-iri add-iri))

(defn add-gene
  "Annotate event with topic genes."
  [event])

(defn add-model 
  "Annotate event with model derived from its value"
  [event]
  (log/debug :fn :add-model :event event :msg :received-event)
  (transform/add-model event))

(def add-model-interceptor
  "Interceptor adding model annotation to stream events"
  (interceptor-enter-def ::add-model add-model))

(defn add-validation
  "Annotate the event with the result of any configured Shacl validation"
  [event]
  (log/debug :fn :add-validation :event event :msg :received-event)
  (let [validate-env (Boolean/valueOf env/validate-events)
        shape-doc-def (-> event ::root-type shapes)]
    (if (and (true? validate-env)
             (some? shape-doc-def))
      (tx
       (let [shape-model (q/get-named-graph (:graph-name shape-doc-def))
             data-model (::q/model event)
             validation-result (validate/validate data-model shape-model)
             did-validate (validate/did-validate? validation-result)
             turtle (q/to-turtle validation-result)
             iri (::iri event)
             root-type (::root-type event)]
         (log/debug :fn :add-validation :root-type root-type :iri iri :did-validate? did-validate :report turtle)
         (assoc event ::validation validation-result ::did-validate did-validate)))
      event)))

(def add-validation-interceptor
  "Interceptor adding shacl validation to stream events.
  Short circuits interceptor chain when data is not validated."
 {:name ::add-validation
  :enter (fn [context] (let [evt (add-validation context)]
                         (if (false? (::did-validate evt))
                           ;; TODO May want to take a different action here...
                           (terminate context)
                           evt)))})

(defn add-subjects-to-event
  "Perform the actual event annotation, returnng the event to th caller"
  [event genes diseases]
  (let [gene-iris (mapv #(str %) genes)
         disease-iris (mapv #(str %) diseases)]
    (assoc event ::subjects {:gene-iris gene-iris :disease-iris disease-iris})))

(defmulti add-subjects ::root-type)

(defmethod add-subjects :sepio/GeneDosageReport [event]
  (log/debug :fn :add-subjects :root-type :sepio/DosageReport :event event :msg :received-event)
  (let [genes (q/select "select ?o where { ?s :iao/is-about ?o }" {} (::q/model event))]
    (add-subjects-to-event event genes [])))

(defmethod add-subjects :sepio/ActionabilityReport [event]
  (log/debug :fn :add-subjects :root-type :sepio/ActionabilityReport :event event :msg :received-event)
  (if-let [act-condition (first (q/select "select ?s where { ?s a :cg/ActionabilityGeneticCondition }" {}
                                          (::q/model event)))]
    (let [genes (q/ld-> act-condition [:sepio/is-about-gene])
          diseases (q/ld-> act-condition [:rdfs/sub-class-of])]
      (add-subjects-to-event event genes diseases))
    event))

(defmethod add-subjects :sepio/GeneValidityReport [event]
  (log/debug :fn :add-subjects :root-type :sepio/GeneValidityReport :event event :msg :received-event)
  (if-let [gv-prop (first (q/select "select ?s where { ?s a :sepio/GeneValidityProposition }" {}
                                    (::q/model event)))]
    (let [genes (q/ld-> gv-prop [:sepio/has-subject])
          diseases (q/ld-> gv-prop [:sepio/has-object])
          modes-of-inheritance (q/ld-> gv-prop [:sepio/has-qualifier])]
      (assoc event ::subjects {:gene-iris (mapv str genes)
                               :disease-iris (mapv str diseases)
                               :moi-iris (mapv str modes-of-inheritance)}))
    event))

(defmethod add-subjects :default [event]
  (log/debug :fn :add-subjects :root-type :default :event event :msg :received-event)
  event)
  
(def add-subjects-interceptor
  "Interceptor adding a subject annotation (gene/disease iris) to stream events"
  (interceptor-enter-def ::add-subjects add-subjects))

(defn add-replaces [event]
  (replaces/add-replaces event))

(def add-replaces-interceptor
  "Interceptor checking to see if an incoming curation should replace any existing records"
  {:name ::add-replaces-interceptor
   :enter add-replaces})
