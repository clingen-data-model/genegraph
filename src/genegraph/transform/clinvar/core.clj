(ns genegraph.transform.clinvar.core
  (:require [cheshire.core :as json]
            [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.clinical-assertion :as clinical-assertion]
            [genegraph.transform.clinvar.common :refer [clinvar-add-event-graphql
                                                        clinvar-add-model
                                                        clinvar-model-to-jsonld]]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.clinvar.variation :as variation]
            [genegraph.transform.types :as xform-types :refer [add-model]]
            [genegraph.util :refer [str->bytestream]]
            [io.pedestal.log :as log]))

(defn add-parsed-value [event]
  (assoc event
         ::parsed-value
         (-> event
             :genegraph.sink.event/value
             (json/parse-string true)
             (util/parse-nested-content))))

(defmethod xform-types/add-data :clinvar-raw [event]
  (log/debug :fn :add-data)
  (try
    (let [event-with-json (add-parsed-value event)
          _ (log/info :fn :genegraph.transform.clinvar.core/add-data
                      :offset (:genegraph.sink.stream/offset event)
                      :entity_type (get-in event-with-json
                                           [::parsed-value :content :entity_type])
                      :id (get-in event-with-json [::parsed-value :content :id]
                                  (get-in event-with-json [::parsed-value :content]))
                      :release_date (get-in event-with-json [::parsed-value :release_date]))
          event-with-data (case (get-in event-with-json
                                        [::parsed-value :content :entity_type])
                            "trait" (clinical-assertion/add-data-for-trait
                                     event-with-json)
                            "trait_set" (clinical-assertion/add-data-for-trait-set
                                         event-with-json)
                            "clinical_assertion" (clinical-assertion/add-data-for-clinical-assertion
                                                  event-with-json)
                            "variation" (variation/add-data-for-variation
                                         event-with-json)
                            event-with-json)]
      event-with-data)
    (catch Exception e
      (log/error :fn ::add-data :msg "Exception caught in add-data for :clinvar-raw"
                 :event event
                 :exception e)
      (throw e))))

(defn get-clinvar-format [value]
  (let [cv-format (keyword (or (get-in value [:entity_type])
                               (get-in value [:content :entity_type])))]
    cv-format))

(defmethod clinvar-add-model :default [event]
  (log/debug :fn :clinvar-add-model :dispatch :default :msg "No multimethod defined for event" :event event)
  ;; Avoids NPE on downstream interceptors expecting a model to exist
  (assoc event ::q/model (l/statements-to-model [])))

(defn add-model-from-contextualized-data [event]
  (if (:genegraph.annotate/data-contextualized event)
    (assoc event ::q/model (l/read-rdf (str->bytestream
                                        (json/generate-string
                                         (:genegraph.annotate/data-contextualized event)))
                                       {:format :json-ld}))
    (-> event
        ;; Construct an empty model so downstream interceptors that try to read it dont get NPE
        (assoc ::q/model (l/statements-to-model []))
        (update :exception conj {:fn :add-model-from-contextualized-data
                                 :msg "Event did not have contextualized data"
                                 :entity-type (-> event :genegraph.transform.clinvar/format)}))))

(defmethod add-model :clinvar-raw
  [event]
  ;; Construct an Apache Jena Model for the message contained in event under :genegraph.sink.event/value.
  ;; Set it to key :genegraph.database.query/model.
  (try
    (let [event (-> event
                    add-parsed-value
                    (#(assoc % :genegraph.transform.clinvar/format (get-clinvar-format (::parsed-value %))))
                    xform-types/add-data
                    add-model-from-contextualized-data)]
      (log/trace :fn :add-model :event event)
      event)
    (catch Exception e
      (log/error :fn :add-model :msg "Exception in clinvar add-model" :exception e)
      (update event :exception conj e))))

(defmethod clinvar-model-to-jsonld :default [event]
  (log/warn :fn ::clinvar-model-to-jsonld
            :dispatch :default
            :msg "No multimethod defined for event"
            :event event)
  event)


(defmethod xform-types/add-event-graphql :clinvar-raw [event]
  (log/info :fn ::add-event-graphql :iri (:genegraph.annotate/iri event))
  (clinvar-add-event-graphql event))

(defmethod xform-types/add-model-jsonld :clinvar-raw [event]
  (log/info :fn ::add-model-jsonld :iri (:genegraph.annotate/iri event))
  (let [j (clinvar-model-to-jsonld event)]
    (assoc event :genegraph.annotate/jsonld j)))
