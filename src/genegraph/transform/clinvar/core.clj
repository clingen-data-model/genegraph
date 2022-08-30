(ns genegraph.transform.clinvar.core
  (:require [genegraph.database.query :as q]
            [genegraph.transform.types :as xform-types :refer [add-model]]
            [cheshire.core :as json]
            [io.pedestal.log :as log]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-add-model
                                                        clinvar-add-data
                                                        clinvar-model-to-jsonld
                                                        clinvar-add-event-graphql]]
            [genegraph.database.load :as l]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.clinvar.variation-new :as variation]
            [genegraph.transform.clinvar.clinical-assertion :as clinical-assertion]))

(defn add-document-store-id [event]
  (let [data (:genegraph.annotate/data event)]
    (assoc event
           :genegraph.sink.document-store/id
           (str (:clinvar_type data) "_" (:clinvar_id data)))))

(defn add-parsed-value [event]
  (log/info :fn :add-parsed-value :event event)
  (assoc event
         ::parsed-value
         (-> event
             :genegraph.sink.event/value
             (json/parse-string true)
             (util/parse-nested-content))))

(defmethod xform-types/add-data :clinvar-raw [event]
  (log/info :fn :add-data)
  (let [event-with-json (add-parsed-value event)
        _ (log/info :entity_type (get-in event-with-json
                                         [::parsed-value :content :entity_type]))
        event-with-data (case (get-in event-with-json
                                      [::parsed-value :content :entity_type])
                          "trait" (clinical-assertion/add-data-for-raw-trait
                                   event-with-json)
                          "trait_set" (clinical-assertion/add-data-for-trait-set
                                       event-with-json)
                          "clinical_assertion" (clinical-assertion/add-data-for-clinical-assertion
                                                event-with-json)
                          "variation" (variation/add-data-for-variation
                                       event-with-json)
                          event-with-json)]
    (add-document-store-id event-with-data)))

(defn get-clinvar-format [value]
  (let [cv-format (keyword (or (get-in value [:entity_type])
                               (get-in value [:content :entity_type])))]
    cv-format))

(defmethod clinvar-add-model :default [event]
  (log/debug :fn :clinvar-add-model :dispatch :default :msg "No multimethod defined for event" :event event)
  ;; Avoids NPE on downstream interceptors expecting a model to exist
  (assoc event ::q/model (l/statements-to-model [])))

(defmethod add-model :clinvar-raw [event]
  "Construct an Apache Jena Model for the message contained in event under :genegraph.sink.event/value.
  Set it to key :genegraph.database.query/model."
  (try
    (let [event (-> event
                    add-parsed-value
                    (#(assoc % :genegraph.transform.clinvar/format (get-clinvar-format (::parsed-value %))))
                    (#(clinvar-add-model %)))]
      (log/trace :fn :add-model :event event)
      event)
    (catch Exception e
      (log/error :fn :add-model :msg "Exception in clinvar add-model" :exception e)
      (assoc event :exception e))))

(defmethod clinvar-model-to-jsonld :default [event]
  (log/debug :fn ::clinvar-model-to-jsonld :dispatch :default :msg "No multimethod defined for event" :event event))


(defmethod xform-types/add-event-graphql :clinvar-raw [event]
  (log/info :fn ::add-event-graphql :iri (:genegraph.annotate/iri event))
  (clinvar-add-event-graphql event))

(defmethod xform-types/add-model-jsonld :clinvar-raw [event]
  (log/info :fn ::add-model-jsonld :iri (:genegraph.annotate/iri event))
  (let [j (clinvar-model-to-jsonld event)]
    (assoc event :genegraph.annotate/jsonld j)))
