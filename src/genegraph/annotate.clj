(ns genegraph.annotate
  (:require [genegraph.annotate.interface :as annotate-interface]
            [genegraph.annotate.action :as action]
            [genegraph.annotate.replaces :as replaces]
            [genegraph.database.query :as q]
            [genegraph.database.validation :as validate]
            [genegraph.database.util :as util :refer [tx]]
            [genegraph.database.instance :as instance]
            [genegraph.env :as env]
            [genegraph.interceptor :as intercept :refer [interceptor-enter-def]]
            [genegraph.transform.core :as transform]
            [genegraph.transform.types :as xform-types :refer [add-model-jsonld]]
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
  (if (and (not (::iri event))
           (::graph-name event))
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

(def add-data-interceptor
  {:name ::add-data
   :enter #(xform-types/add-data %)})

(defn add-validation-shape
  "Annotate the event with the appropriate shape for validation
  if it exists in the database and if a shape has not already been
  added (during development, for example)."
  [event]
  (if (and env/validate-events
           (not (contains? event ::validation-shape)))
    (assoc event
           ::validation-shape
           (some-> event
                   ::root-type
                   shapes
                   :graph-name
                   q/get-named-graph))
    event))

(def add-validation-shape-interceptor
  {:name ::add-validation-shape
   :enter add-validation-shape})

(defn add-validation-context
  "Annotate the event with a model to join to prior to validation.
  This model should include any valuesets and data necessary for
  the model and shape to pass validation"
  [event]
  (let [context-graph-list (some-> event ::root-type shapes :validation-context)]
    (if (and env/validate-events
             (not (::validation-context event))
             context-graph-list)
      (assoc event
             ::validation-context
             (apply q/union (map q/get-named-graph context-graph-list)))
      event)))

(def add-validation-context-interceptor
  {:name ::add-validation-context-interceptor
   :enter add-validation-context})

(defn add-validation
  "Annotate the event with the result of any configured Shacl validation"
  [event]
  (if (::validation-shape event)
    (let [validation-model (if (::validation-context event)
                             (q/union (::q/model event) (::validation-context event))
                             (::q/model event))
          validation-result (validate/validate validation-model
                                               (::validation-shape event))
          did-validate (validate/did-validate? validation-result)]
      ;; (println (type (::validation-shape event)))
      ;; (println "validating")
      (assoc event ::validation validation-result ::did-validate did-validate))
    event))

(def add-validation-interceptor
  "Interceptor adding shacl validation to stream events.
  Short circuits interceptor chain when data is not validated."
  {:name ::add-validation
   :enter (fn [context] (let [evt (add-validation context)]
                          (if (false? (::did-validate evt))
                            (terminate evt)
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
  (log/debug :fn :add-subjects :root-type :sepio/ActionabilityReport :msg :received-event)
  (add-subjects-to-event event
                         (q/select "select distinct ?gene where { ?s a :cg/ActionabilityGeneticCondition ; :sepio/is-about-gene ?gene  }"
                                   {}
                                   (::q/model event))
                         (q/select "select distinct ?disease where { ?s a :cg/ActionabilityGeneticCondition ; :rdfs/sub-class-of ?disease  }"
                                   {}
                                   (::q/model event))))

(defmethod add-subjects :sepio/GeneValidityReport [event]
  (log/debug :fn :add-subjects :root-type :sepio/GeneValidityReport :msg :received-event)
  (if-let [gv-prop (first (q/select "select ?s where { ?s a :sepio/GeneValidityProposition }" {}
                                    (::q/model event)))]
    (let [genes (q/ld-> gv-prop [:sepio/has-subject])
          diseases (q/ld-> gv-prop [:sepio/has-object])
          modes-of-inheritance (q/ld-> gv-prop [:sepio/has-qualifier])
          affiliations (q/ld-> gv-prop [[:sepio/has-subject :<]
                                        :sepio/qualified-contribution
                                        :sepio/has-agent])]
      (assoc event ::subjects {:gene-iris (mapv str genes)
                               :disease-iris (mapv str diseases)
                               :moi-iris (mapv str modes-of-inheritance)
                               :agent-iris (mapv str affiliations)}))
    event))

(defmethod add-subjects :sepio/GeneValidityProposition [event]
  (log/debug :fn :add-subjects :root-type :sepio/GeneValidityProposition :msg :received-event)
  (if-let [gv-prop (first (q/select "select ?s where { ?s a :sepio/GeneValidityProposition }" {}
                                    (::q/model event)))]
    (let [genes (q/ld-> gv-prop [:sepio/has-subject])
          diseases (q/ld-> gv-prop [:sepio/has-object])
          modes-of-inheritance (q/ld-> gv-prop [:sepio/has-qualifier])
          affiliations (q/ld-> gv-prop [[:sepio/has-subject :<]
                                        :sepio/qualified-contribution
                                        :sepio/has-agent])]
      (assoc event ::subjects {:gene-iris (mapv str genes)
                               :disease-iris (mapv str diseases)
                               :moi-iris (mapv str modes-of-inheritance)
                               :agent-iris (mapv str affiliations)}))
    event))

(defmethod add-subjects :default [event]
  (log/debug :fn :add-subjects :root-type :default :msg :received-event)
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

(def add-jsonld-interceptor
  {:name ::add-jsonld-interceptor
   :enter (fn [event] (xform-types/add-model-jsonld event))})

(defmethod annotate-interface/add-dataset :default [event]
  (assoc event ::dataset instance/db))

(def add-dataset-interceptor
  {:name ::add-dataset-interceptor
   :enter annotate-interface/add-dataset})

(defn add-command [event command]
  (update event ::dataset-commands conj command))

(defmethod annotate-interface/add-dataset-commands :default [event]
  (cond-> event
    (= :publish (::action event)) (add-command 
                                   {:command :replace
                                    :model-name (::iri event)
                                    :model (::q/model event)})
    (= :unpublish (::action event)) (add-command 
                                     {:command :remove
                                      :model-name (::iri event)})
    (= :unpublish (::action event)) (add-command 
                                     {:command :remove
                                      :model-name (::iri event)})
    (::replaces event) (add-command
                        {:command :remove
                         :model-name (::replaces event)})))

(def add-dataset-commands-interceptor
  {:name ::add-dataset-commands-interceptor
   :enter annotate-interface/add-dataset-commands})
