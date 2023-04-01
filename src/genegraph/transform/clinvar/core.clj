(ns genegraph.transform.clinvar.core
  (:require [cheshire.core :as json]
            [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.clinical-assertion :as clinical-assertion]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.clinvar.variation :as variation]
            [genegraph.transform.types :as xform-types :refer [add-model]]
            [genegraph.util :refer [str->bytestream]]
            [io.pedestal.log :as log]))

(defn add-parsed-value
  "Adds ::parsed-value containing the keywordized edn-map of :genegraph.sink.event/value.
   Also parses the json-encoded string under []::parse-value :content :content], but does not keywordize this"
  [event]
  (assoc event
         ::parsed-value
         (-> event
             :genegraph.sink.event/value
             (json/parse-string true)
             (util/parse-nested-content))))

(def data-fns-for-type
  {"trait" #(clinical-assertion/add-data-for-trait %)
   "trait_set" #(clinical-assertion/add-data-for-trait-set %)
   "clinical_assertion" #(clinical-assertion/add-data-for-clinical-assertion %)
   "variation" #(variation/add-data-for-variation %)})

(defn add-event-type [event]
  (let [message (::parsed-value event)
        event-type (when (#{"create" "update" "delete"} (:event_type message))
                     (keyword (:event_type message)))]
    (assoc event :genegraph.annotate/event-type event-type)))

(defmethod xform-types/add-data :clinvar-raw [event]
  (try
    (let [event (-> event
                    add-parsed-value
                    add-event-type)]
      (log/debug :fn :genegraph.transform.clinvar.core/add-data
                 :offset (:genegraph.sink.stream/offset event)
                 :entity_type (get-in event [::parsed-value :content :entity_type])
                 :id (get-in event [::parsed-value :content :id]
                             (get-in event [::parsed-value :content]))
                 :release_date (get-in event [::parsed-value :release_date]))
      (let [entity-type (get-in event [::parsed-value :content :entity_type])
            add-data-fn (get data-fns-for-type entity-type identity)
            event-with-data (add-data-fn event)]
        (if (:genegraph.annotate/data event-with-data)
          (update event-with-data :genegraph.annotate/data
                  (fn [data] (-> data
                                 (common/remove-key-recur "@context")
                                 (common/remove-key-recur (keyword "@context")))))
          event-with-data)))
    (catch Exception e
      (log/error :fn ::add-data :msg "Exception caught in add-data for :clinvar-raw"
                 :event event
                 :exception e)
      (throw e))))

(defn get-clinvar-format [value]
  (let [cv-format (keyword (or (get-in value [:entity_type])
                               (get-in value [:content :entity_type])))]
    cv-format))

(defmethod common/clinvar-add-model :default [event]
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
        ;; Add a warning if this type has a transformer registered but no data was added
        (#(let [entity-type (get-in % [::parsed-value :content :entity_type])]
            (cond-> %
              (data-fns-for-type entity-type)
              (update :warning conj {:fn :add-model-from-contextualized-data
                                     :msg "Event did not have contextualized data"
                                     :entity-type (-> % :genegraph.transform.clinvar/format)})))))))

(defmethod add-model :clinvar-raw
  [event]
  ;; Construct an Apache Jena Model for the message contained in event under :genegraph.sink.event/value.
  ;; Set it to key :genegraph.database.query/model.
  (try
    (let [event (-> event
                    add-parsed-value
                    (#(assoc % :genegraph.transform.clinvar/format (get-clinvar-format (::parsed-value %))))
                    ;; Add data interceptor should be called before add-model.
                    ;; Leaving this in as a fallback if it is not.
                    ((fn [e] (if (not (:genegraph.annotate/data e))
                               (xform-types/add-data e)
                               e)))
                    add-model-from-contextualized-data)]
      (log/trace :fn :add-model :event event)
      event)
    (catch Exception e
      (log/error :fn :add-model :msg "Exception in clinvar add-model" :exception e)
      (update event :exception conj e))))

(defmethod common/clinvar-model-to-jsonld :default [event]
  (log/warn :fn ::clinvar-model-to-jsonld
            :dispatch :default
            :msg "No multimethod defined for event"
            :event event)
  event)


(defmethod xform-types/add-event-graphql :clinvar-raw [event]
  (log/info :fn ::add-event-graphql :iri (:genegraph.annotate/iri event))
  (common/clinvar-add-event-graphql event))

(defmethod xform-types/add-model-jsonld :clinvar-raw [event]
  (log/info :fn ::add-model-jsonld :iri (:genegraph.annotate/iri event))
  (let [j (common/clinvar-model-to-jsonld event)]
    (assoc event :genegraph.annotate/jsonld j)))
