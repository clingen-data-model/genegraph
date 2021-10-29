(ns genegraph.sink.event
  (:require [genegraph.env :as env]
            [genegraph.database.query :as q]
            [genegraph.database.load :refer [load-model remove-model]]
            [genegraph.database.util :refer [begin-write-tx close-write-tx write-tx]]
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
            [genegraph.sink.stream :as stream :refer [consumer-thread]]
            [mount.core :as mount :refer [defstate]]
            [io.pedestal.interceptor :as intercept]
            [io.pedestal.interceptor.chain :as chain :refer [terminate]]
            [io.pedestal.interceptor.helpers :as helper]
            [io.pedestal.log :as log]
            [clojure.spec.alpha :as spec]))

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
    (when (= :publish (::ann/action event))
      (log/debug :fn :add-to-db! :root-type root-type :iri iri :msg :loading)
      (load-model (::q/model event) iri)))
  event)

(def add-to-db-interceptor
  "Interceptor adding stream events to the database."
  (interceptor-enter-def ::add-to-db add-to-db!))

(defn unpublish
  [event]
  (when (= :unpublish (::ann/action event))
    (log/debug :fn ::unpublish :root-type (::ann/root-type event) :iri (::ann/iri event))
    (remove-model (::ann/iri event)))
  event)

(def unpublish-interceptor
  "Interceptor handling unpublish events in the database (removing/soft deleting)."
  {:name ::unpublish
   :enter unpublish})

(defn replace-curation
  [event]
  (when (::ann/replaces event)
    (log/debug :fn ::replace :root-type (::ann/root-type event) :iri (str (::ann/replaces event)))
    (remove-model (str (::ann/replaces event))))
  event)

(def replace-interceptor
  "Interceptor for removing replaced curations"
  {:name ::replace
   :enter replace-curation})

(def log-result-interceptor
  {:name ::log-result
   :leave (fn [e] (log/debug
                   :fn :log-result-interceptor
                   :event (select-keys e [::ann/iri ::ann/subjects ::ann/did-validate])) e)})

(def abort-on-dry-run-interceptor
  {:name ::abort-on-dry-run
   :enter (fn [e]
            (if (::dry-run e)
              (terminate e)
              e))})

(defn stream-producer [event]
  (if-let [producer-topic (stream/transformer-topic-for (::stream/topic event))]
    (when (= :publish (::ann/action event))
      (let [iri (::ann/iri event)
            model (::q/model event)
            producer (stream/producer-for-topic! producer-topic)
            producer-record (stream/producer-record-for producer-topic iri model)
            future (.send producer producer-record)]
      (assoc event ::record-metadata (.get future))))
    event))

(def stream-producer-interceptor
  "Interceptor for producing message to an output stream"
  {:name ::stream-producer-interceptor
   :enter stream-producer})

(def transformer-interceptor-chain [ann/add-metadata-interceptor
                                    ann/add-model-interceptor
                                    ann/add-iri-interceptor
                                    ann/add-validation-shape-interceptor
                                    ann/add-validation-context-interceptor
                                    ann/add-validation-interceptor
                                    ann/add-subjects-interceptor
                                    ann/add-action-interceptor
                                    ann/add-replaces-interceptor
                                    abort-on-dry-run-interceptor
                                    add-to-db-interceptor
                                    unpublish-interceptor
                                    replace-interceptor
                                    stream-producer-interceptor])

(def web-interceptor-chain [log-result-interceptor
                            ann/add-metadata-interceptor
                            ann/add-model-interceptor
                            ann/add-iri-interceptor
                            ann/add-validation-shape-interceptor
                            ann/add-validation-context-interceptor
                            ann/add-validation-interceptor
                            ann/add-subjects-interceptor
                            ann/add-action-interceptor
                            ann/add-replaces-interceptor
                            abort-on-dry-run-interceptor
                            add-to-db-interceptor
                            unpublish-interceptor
                            replace-interceptor
                            suggest/update-suggesters-interceptor
                            cache/expire-resolver-cache-interceptor
                            response-cache/expire-response-cache-interceptor])

(defn interceptor-chain []
  (if (true? env/transformer-mode)
    transformer-interceptor-chain
    web-interceptor-chain))

(defn inject-trace-into-interceptor-chain
  "Modifies the interceptor chain so that For every interceptor in the chain,
  adds an interceptor right after it that logs the name of the interceptor into
  the :executed-interceptors vector in the event for tracking purposes."
  [interceptors]
  (reduce (fn [vec intercept]
            (conj vec 
                  (helper/before (fn [e] (let [now-ms (inst-ms (java.util.Date.))]
                                           (assoc e :executed-interceptors
                                                  (conj (get e :executed-interceptors [])
                                                        (:name intercept))
                                                  :interceptor-start-ms now-ms))))
                  intercept
                  (helper/before (fn [e] (let [now-ms (inst-ms (java.util.Date.))
                                               start-ms (:interceptor-start-ms e)]
                                           (if start-ms
                                             (assoc e :executed-interceptors
                                                    (conj (:executed-interceptors e)
                                                          (keyword (str (- now-ms start-ms) "ms"))))
                                             e))))))
          []
          interceptors))

(defn process-event! [event]
  (log/debug :fn :process-event! :event event :msg :event-received)
  (-> event 
      (chain/enqueue (map #(intercept/interceptor %)
                          (inject-trace-into-interceptor-chain (interceptor-chain))))
      chain/execute))

(defn process-event-seq!
  ([event-seq]
   (process-event-seq! event-seq {}))
  ([event-seq opts]
   (write-tx 
    (doseq [e event-seq]
      (process-event! (merge e opts))))))

(defstate event-processing
  :start (stream/start-consumers process-event-seq!))
