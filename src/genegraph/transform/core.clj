(ns genegraph.transform.core
  (:require [genegraph.database.load :as l]
            [clojure.java.io :as io]
            [genegraph.transform.types :as xform-types]
            [genegraph.transform.dosage-jira]
            [genegraph.transform.gci]
            [genegraph.transform.gene]
            [genegraph.transform.omim]
            [genegraph.transform.gci-legacy]
            [genegraph.transform.gci-legacy-report-only]
            [genegraph.transform.features]
            [genegraph.transform.gci-express]
            [genegraph.transform.affiliations]
            [genegraph.transform.hi-index]
            [genegraph.transform.loss-intolerance]
            [genegraph.transform.gci-neo4j]
            ;;[genegraph.transform.gene-validity]
            [genegraph.transform.gene-validity-refactor]
            [genegraph.transform.actionability]
            [genegraph.transform.clinvar.core]
            [genegraph.transform.rxnorm]
            [genegraph.env :as env]
            [genegraph.transform.core :as xform]
            [io.pedestal.log :as log]))

(defn add-model [event]
  (try
    (xform-types/add-model event)
    (catch Exception e 
      (assoc event :exception (str e)))))

(defn transform-doc [doc]
  (xform-types/transform-doc doc))

(defmethod xform-types/transform-doc :rdf [doc-def] 
  (with-open [is (if (:target doc-def)
                   (io/input-stream (str (xform-types/target-base) (:target doc-def)))
                   (-> doc-def :document .getBytes java.io.ByteArrayInputStream.))] 
    (l/read-rdf is (:reader-opts doc-def))))

(defn add-model-with-format [event format]
  (with-open [is (cond 
                   (:genegraph.sink.event/value event)
                   (-> event :genegraph.sink.event/value .getBytes java.io.ByteArrayInputStream.)
                   (:genegraph.sink.base/document event)
                   (io/input-stream (str (xform-types/target-base) (:genegraph.sink.base/document event))))] 
    (assoc event :genegraph.database.query/model (l/read-rdf is {:format format}))))

(defmethod xform-types/add-model :rdf-xml [event] 
  (add-model-with-format event :rdf-xml))

(defmethod xform-types/add-model :json-ld [event] 
  (add-model-with-format event :json-ld))

(defmethod xform-types/add-model :turtle [event] 
  (add-model-with-format event :turtle))


