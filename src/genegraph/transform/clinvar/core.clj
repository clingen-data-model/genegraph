(ns genegraph.transform.clinvar.core
  (:require [genegraph.database.query :as q]
            [genegraph.transform.types :as xform-types :refer [add-model]]

            [genegraph.database.names :refer [prefix-ns-map]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [io.pedestal.log :as log]
            [genegraph.transform.clinvar.common :refer [transform-clinvar
                                                        clinvar-to-model
                                                        clinvar-model-to-jsonld
                                                        clinvar-add-event-graphql]]
            [genegraph.transform.clinvar.util :as util]
    ;[genegraph.transform.clinvar.variation-archive]
            [genegraph.transform.clinvar.variation]
            [genegraph.database.load :as l]
            [genegraph.transform.clinvar.common :as common]))

;(defmethod clinvar-to-jsonld :release_sentinel
;  [msg]
;  (let [content (:content msg)
;        iri (str iri/release-sentinel (:release_date msg))]
;    [[iri :cg/clingen-release (:release_date msg)]]))

;(defmethod transform-clinvar :default
;  [msg]
;  ; entity_type did not match a defmethod for transform-clinvar
;  (cond
;    (= "release_sentinel" (get msg :type))
;    (do (log/debug "Got release sentinel" msg)
;        (transform-sentinel msg))
;    :default
;    (do (log/error (ex-info (str ::format " not known") {:cause msg}))
;        ; Return no triples
;        [])))

(defn get-clinvar-format [value]
  (let [cv-format (keyword (or (get-in value [:entity_type])
                               (get-in value [:content :entity_type])))]
    cv-format))

(defn select-other-keys
  "Inverse of select-keys in that it selects all the keys other than those specified.
  exclude-ks should be a seq of value which may be a key in m"
  [m exclude-ks]
  (select-keys m (filter
                   ; Return true if key from m is not in keys to exclude
                   (fn [m-key] (nil? (some #(= m-key %) exclude-ks)))
                   (keys m))))

(defmethod clinvar-to-model :default [event]
  (log/debug :fn ::clinvar-to-model :dispatch :default :msg "No multimethod defined for event" :event event)
  ; Avoids NPE on downstream interceptors expecting a model to exist
  (l/statements-to-model [])
  ;(assoc event ::q/model )
  )

(defmethod add-model :clinvar-raw [event]
  "Construct an Apache Jena Model for the message contained in event under :genegraph.sink.event/value.
  Set it to key :genegraph.database.query/model."
  (try
    (let [event (-> event
                    (#(assoc % ::parsed-value (-> %
                                                  :genegraph.sink.event/value
                                                  (json/parse-string true))))
                    ;((fn [event] (log/info :event event) event))
                    ;(#(assoc % ::parsed-value (-> % ::parsed-value util/parse-nested-content)))
                    ;((fn [event] (log/info :msg "parsed content" :parsed-value (::parsed-value event)) event))
                    (#(assoc % :genegraph.transform.clinvar/format (get-clinvar-format (::parsed-value %))))
                    (#(assoc % ::q/model (clinvar-to-model %))))]
      (log/debug :fn ::add-model :event event)
      event)
    (catch Exception e
      (log/error :fn ::add-model :msg "Exception in clinvar add-model" :exception e)
      (assoc event :exception e))))

(defmethod clinvar-model-to-jsonld :default [event]
  (log/debug :fn ::clinvar-model-to-jsonld :dispatch :default :msg "No multimethod defined for event" :event event))


(defmethod xform-types/add-event-graphql :clinvar-raw [event]
  (log/info :fn ::add-event-graphql :event event)
  (clinvar-add-event-graphql event))

(defmethod xform-types/add-model-jsonld :clinvar-raw [event]
  (log/debug :fn ::add-model-jsonld :event event)
  (let [j (clinvar-model-to-jsonld event)]
    (assoc event :genegraph.annotate/jsonld j)))
