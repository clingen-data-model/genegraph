(ns genegraph.transform.clinvar.core
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.types :as xform-types :refer [add-model]]
            [genegraph.database.names :refer [prefix-ns-map]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [genegraph.transform.clinvar.iri :as iri]
            [genegraph.transform.clinvar.common :refer [transform-clinvar clinvar-to-jsonld]]

            [genegraph.transform.clinvar.jsonld.clinical-assertion]
    ;[genegraph.transform.clinvar.jsonld.submission]
            [genegraph.transform.clinvar.jsonld.variation-archive]
            [genegraph.transform.clinvar.jsonld.variation]
            )
  (:import (java.io StringReader)))

(defn transform-sentinel
  [msg]
  (let [content (:content msg)
        iri (str iri/release-sentinel (:release_date msg))]
    [[iri :cg/clingen-release (:release_date msg)]]))

(defmethod transform-clinvar :default
  [msg]
  ; entity_type did not match a defmethod for transform-clinvar
  (cond
    (= "release_sentinel" (get msg :type))
    (do (log/info "Got release sentinel" msg)
        (transform-sentinel msg))
    :default
    (do (log/error (ex-info (str ::format " not known") {:cause msg}))
        ; Return no triples
        [])))

;(defmethod add-model :clinvar-raw [event]
;  (let [model (-> event
;                  :genegraph.sink.event/value
;                  (json/parse-string true)
;                  ((fn [%] (assoc % :genegraph.transform.clinvar/format
;                                    (keyword (get-in % [:content :entity_type])))))
;                  transform-clinvar
;                  ;((fn [triples] (log/info (json/generate-string triples)) triples))
;                  ; Some statement objects are optional, assume spec already performed,
;                  ; filter null objects here
;                  ((fn [triples] (filter #(let [[s p o] %] (not= nil o)) triples)))
;                  ((fn [triples] (log/info (json/generate-string triples)) triples))
;                  l/statements-to-model)]
;    (assoc event ::q/model model)))

;(defmulti transform-clinvar-jsonld :genegraph.transform.clinvar/format)
;(defmethod transform-clinvar-jsonld :clinical_assertion [record]
;  (genegraph.transform.clinvar.jsonld.clinical-assertion/t ))


(defn add-clinvar-format [event]
  (let [cv-format (keyword (get-in event [:entity_type]))]
    (log/info cv-format)
    (assoc event :genegraph.transform.clinvar/format cv-format)))

(defn add-clinvar-jsonld
  "Expects `msg` to be a kafka message value, parsed and keywordized.
  Of the form from genegraph.sink under :genegraph.sink.event/value."
  [msg]
  (let [jsonld (-> msg
                   add-clinvar-format
                   clinvar-to-jsonld)]
    (log/info jsonld)
    (assoc msg ::clinvar-jsonld jsonld)))

(defn select-other-keys
  "Inverse of select-keys in that it selects all the keys other than those specified."
  [m exclude-ks]
  (select-keys m (filter
                   ; Return true if key from m is not in keys to exclude
                   (fn [m-key] (nil? (some #(= m-key %) exclude-ks)))
                   (keys m))))

; add-model with json-ld based triples
(defmethod add-model :clinvar-combined [event]
  "Construct an Apache Jena Model for the message contained in event under :genegraph.sink.event/value.
  Add it to :genegraph.database.query/model.
  This function uses a json-ld intermediary translation from add-clinvar-jsonld"

  (let [info-keys [:genegraph.transform.core/format
                   :genegraph.sink.stream/topic
                   :genegraph.sink.stream/partition
                   :genegraph.sink.stream/offset]]
    (log/info "add-model" :clinvar-combined (select-keys event info-keys))
    (log/debug "add-model" :clinvar-combined (select-other-keys event info-keys)))

  (let [model (-> event
                  :genegraph.sink.event/value
                  (json/parse-string true)
                  ;add-clinvar-format
                  add-clinvar-jsonld
                  :genegraph.transform.clinvar.core/clinvar-jsonld
                  ; TODO convert ::clinvar-jsonld to model using l/read-rdf
                  (#(l/read-rdf (StringReader. (json/generate-string %)) {:format :json-ld}))
                  )
        event (assoc event ::q/model model)]
    ;(log/info event)
    event))

; add-model with manually construct triples
;(defmethod add-model :clinvar-raw [event]
;  (let [model (-> event
;                  :genegraph.sink.event/value
;                  (json/parse-string true)
;                  add-clinvar-format
;                  transform-clinvar
;                  ;((fn [triples] (log/info (json/generate-string triples)) triples))
;                  ; Some statement objects are optional, assume spec already performed,
;                  ; filter null objects here
;                  ((fn [triples] (filter #(let [[s p o] %] (not= nil o)) triples)))
;                  ((fn [triples] (log/info (json/generate-string triples)) triples))
;                  l/statements-to-model)]
;    (assoc event ::q/model model)))
