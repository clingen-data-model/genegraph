(ns genegraph.transform.gci-legacy-report-only
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q :refer [resource]]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]
            [clojure.string :as s]
            [cheshire.core :as json]
            [io.pedestal.log :as log]
            [clojure.spec.alpha :as spec]))

(spec/def ::curation
  (spec/keys :req-un [::iri]))

(def gci-root "http://dataexchange.clinicalgenome.org/gci/")
(def affiliation-root "http://dataexchange.clinicalgenome.org/agent/")

(defn json-content-node [report iri]
  [[iri :rdf/type :cnt/ContentAsText]
   [iri :cnt/chars (json/encode report)]])

(defn gci-legacy-report-to-triples [report]
  (let [root-version (str gci-root (-> report :iri))
        id (:iri report) 
        iri (resource (str gci-root id "_legacy_report"))
        content-id (l/blank-node)
        assertion-id (resource (str gci-root "assertion_" id))
        result (concat [[iri :rdf/type :sepio/GeneValidityReport]
                        [iri :bfo/has-part content-id]
                        [iri :bfo/has-part assertion-id]]
                       (json-content-node report content-id))]
    result)) 

(defmethod transform-doc :gci-legacy-report-only [doc-def]
  (let [report-json (json/parse-string (:document doc-def) true)]
    (l/statements-to-model (gci-legacy-report-to-triples report-json))))

(defmethod add-model :gci-legacy-report-only [event]
  (log/debug :fn :add-model :format :gci-legacy :event event :msg :received-event)
  (let [report-json (json/parse-string (:genegraph.sink.event/value event) true)]
    (if (spec/valid? ::curation report-json)
      (assoc event
             ::q/model
             (l/statements-to-model  (gci-legacy-report-to-triples report-json)))
      (assoc event ::spec/invalid true))))
  
