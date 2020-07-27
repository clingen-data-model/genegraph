(ns genegraph.sink.event
  (:require [genegraph.database.query :as q]
            [genegraph.database.load :refer [load-model]]
            [genegraph.annotate :as annotate :refer [add-model add-iri add-metadata]]
            [genegraph.source.graphql.common.cache :as cache]))

(defn add-to-db! [event]
  (load-model (::q/model event) (::annotate/iri event))
  event)

(defn process-event! [event]
  (let [processed-event (-> event
                            add-metadata
                            add-model
                            add-iri
                            add-to-db!)]
    (cache/reset-cache!)
    processed-event))
