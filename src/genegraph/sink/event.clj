(ns genegraph.sink.event
  (:require [genegraph.database.query :as q]
            [genegraph.database.load :refer [load-model remove-model]]
            [genegraph.source.graphql.common.cache :as cache]
            [genegraph.response-cache :as response-cache]
            [genegraph.database.validation :as v]
            [genegraph.interceptor :as ggintercept :refer [interceptor-enter-def]]
            [genegraph.annotate :as ann :refer [add-model-interceptor
                                                add-iri-interceptor
                                                add-metadata-interceptor
                                                add-validation-interceptor
                                                add-subjects-interceptor]]
            [genegraph.suggest.suggesters :as suggest :refer [update-suggesters-interceptor]]
            [mount.core :as mount :refer [defstate]]
            [io.pedestal.interceptor :as intercept]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log]))

(def interceptor-chain '[::ann/add-metadata-interceptor
                         ::ann/add-model-interceptor
                         ::ann/add-iri-interceptor
                         ::ann/add-validation-interceptor
                         ::ann/add-subjects-interceptor
                         ::ann/add-action-interceptor
                         ::add-to-db-interceptor
                         ::unpublish-interceptor
                         ::suggest/update-suggesters-interceptor
                         ::cache/expire-resolver-cache-interceptor
                         ::response-cache/expire-response-cache-interceptor])

(def context (atom {}))

(defn add-to-db!
  "Adds model data to the db. As validation is configurable, this is done 
   for events that have been successfully validated, as well as those for 
   which there is no validation configured (see shapes.edn). On successful
   update of the db, annotates the event with :genegraph.sink.event/added-to-db
  true or false"
  [event]
  (let [iri  (::ann/iri event)
        root-type (::ann/root-type event)]
    (log/debug :fn :add-to-db! :root-type root-type :iri iri :msg :loading)
    (load-model (::q/model event) iri)
    event))

(def add-to-db-interceptor
  "Interceptor adding stream events to the database."
  (interceptor-enter-def ::add-to-db add-to-db!))

(defn unpublish
  [event]
  (when (= :unpublish (::ann/action event))
    (log/info :fn ::unpublish :root-type (::ann/root-type event) :iri (::ann/iri event))
    (remove-model (::ann/iri event)))
  event)

(def unpublish-interceptor
  {:name ::unpublish
   :enter unpublish})

(defn process-event! [event]
  (log/debug :fn :process-event! :event event :msg :event-received)
  (swap! context #(merge % event))
  (chain/execute @context))

(defstate interceptor-context
  :start (reset! context (chain/enqueue @context
                                        (map #(intercept/interceptor (symbol %))
                                             interceptor-chain))))
