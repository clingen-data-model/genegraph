(ns genegraph.sink.event
  (:require [genegraph.database.query :as q]
            [genegraph.database.load :refer [load-model]]
            [genegraph.source.graphql.common.cache :as cache]
            [genegraph.database.validation :as v]
            [genegraph.annotate :as annotate :refer [add-model add-iri add-metadata add-validation add-subjects]]
            [io.pedestal.log :as log]))

(defn add-to-db!
  "Adds model data to the db. As validation is configurable, this is done 
   for events that have been successfully validated, as well as those for 
   which there is no validation configured (see shapes.edn). On successful
   update of the db, annotates the event with :genegraph.sink.event/added-to-db
  true or false"
  [event]
  (let [validation-result (::annotate/validation event)
        iri  (::annotate/iri event)
        root-type (::annotate/root-type event)]
    (if (or (nil? validation-result)
            (v/did-validate? validation-result))
      (let [result (load-model (::q/model event) iri)]
        (log/info :fn :add-to-db! :root-type root-type :iri iri :msg "added to db") 
        (assoc event ::added-to-db true))
      (do
        (log/info :fn :add-to-db! :root-type root-type :iri iri :msg "not added to db")
        (assoc event ::added-to-db false)))))

(defn process-event! [event]
  (let [processed-event (-> event
                            add-metadata
                            add-model
                            add-iri
                            add-validation
                            add-subjects
                            add-to-db!)]
    (cache/reset-cache!)
    processed-event))
