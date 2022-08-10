(ns genegraph.transform.clinvar.variation-transformer-test
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [clojure.test :refer [use-fixtures]]
            [clojure.pprint :refer [pprint]]
            [genegraph.transform.clinvar.core]
            [genegraph.transform.clinvar.variation :as variation]
            [genegraph.transform.types :as xform-types]
            [io.pedestal.log :as log]
            [mount.core]))

(defn fixtures [f]
  (mount.core/start #'genegraph.database.instance/db #'genegraph.database.property-store/property-store)
  (f))

(use-fixtures :once fixtures)

(defn eventify [input-map]
  {:genegraph.sink.event/value (json/generate-string input-map)
   :genegraph.transform.core/format :clinvar-raw
   :genegraph.annotate/graph-name :cg/ClinVarObject})

(defn to-events [values]
  (map eventify values))

(defn add-models [events]
  (map xform-types/add-model events))

(defn add-model-jsonlds [events]
  (map xform-types/add-model-jsonld events))

(defn get-variant-messages []
  (-> "clinvar-raw-testdata_20210412.txt"
      slurp
      (clojure.string/split #"\n")
      (->> (map #(json/parse-string % true))
           (filter #(= "variation" (get-in % [:content :entity_type]))))))

(defn test-variation-to-model []
  (let [variant-messages (->> (get-variant-messages)
                              (filter #(= "12610" (get-in % [:content :id])))
                              (take 1)
                              (map eventify)
                              (map #(xform-types/add-model %)))]))


(defn test-vrs-normalization1 []
  (let [events (->> (get-variant-messages)
                    (filter #(= "12610" (get-in % [:content :id])))
                    (take 1)
                    (map eventify)
                    (map genegraph.transform.clinvar.core/add-parsed-value)
                    (map #(xform-types/add-model %))
                    #_(map #(xform-types/add-model-jsonld %)))]
    events))

(defn test-add-model-stepwise []
  (mount.core/start #'genegraph.database.instance/db)
  (def event (->> (get-variant-messages)
                  (filter #(= "12610" (get-in % [:content :id])))
                  (take 1)
                  (map eventify)
                  (map genegraph.transform.clinvar.core/add-parsed-value)
                  first))
  (-> event
      :genegraph.transform.clinvar.core/parsed-value
      variation/variation-preprocess
      (#(do (pprint %) %))
      variation/add-variation-triples
      (#(do (log/info :triples (:genegraph.transform.clinvar.variation/triples %)) %))
      (#(do (pprint (:genegraph.transform.clinvar.variation/triples %)) %))
      (#(do (def with-triples %) %))
      ((fn [annotated-message]
         (let [{triples :genegraph.transform.clinvar.variation/triples
                deferred-triples :genegraph.transform.clinvar.variation/deferred-triples}
               annotated-message]
           (assoc annotated-message
                  :genegraph.transform.clinvar.variation/combined-triples
                  (concat triples
                          (for [deferred-triple deferred-triples]
                            (let [{generator :generator} deferred-triple]
                              (let [realized (generator)]
                                (log/info :realized realized)
                                realized))))))))
      (#(do (def with-combined-triples %) %))))
