(ns genegraph.transform.clinvar.common-test
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.transform.clinvar.core :as core]
            [genegraph.transform.clinvar.variation :as variation]
            [cheshire.core :as json]
            [io.pedestal.log :as log]
            [clojure.java.io :as io]
            ))


(defn get-variant-messages []
  (-> "genegraph/transform/clinvar/variants-with-spdi.txt"
      io/resource
      slurp
      (clojure.string/split #"\n")
      (->> (map #(json/parse-string % true)))))

(defn test-titanium-compaction []
  (let [variant-messages (get-variant-messages)
        model (-> variant-messages
                  first
                  (variation/variation-triples)
                  l/statements-to-model
                  (#(identity {
                               ::q/model %
                               ;:genegraph.transform.core/format "clinvar-raw"
                               ;:genegraph.transform.clinvar/format :variation
                               }))
                  ::q/model)]
    model
    (let [j (-> model
                (jsonld/model-to-jsonld)
                (jsonld/jsonld-to-jsonld-framed (json/generate-string variation/variation-frame))
                (jsonld/jsonld-compact (json/generate-string variation/variation-context)))]
      j)
    ))


;(defn test-fn-titanium []
;  (let [kafka-messages (-> "vcv-messages.txt" io/file slurp (#(s/split % #"\n")) (->> (map #(json/parse-string % true))))
;        triples (-> kafka-messages first ((eval 'variation-archive-v1)))
;        model ^Model (l/statements-to-model triples)]
;    (model-framed-to-jsonld model variation-archive-frame)))
