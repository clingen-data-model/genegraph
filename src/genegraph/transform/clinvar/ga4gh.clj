(ns genegraph.transform.clinvar.ga4gh
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [genegraph.annotate :as ann]
            [genegraph.sink.event :refer [add-to-db!]]
            [genegraph.database.query :as q]
            [genegraph.transform.types :as xform-types]
            [genegraph.transform.clinvar.clinical-assertion :as ca]
            [genegraph.transform.clinvar.common :as common]
            [mount.core :as mount]))


(defn start-states! []
  (mount/start
   #'genegraph.database.instance/db
   #'genegraph.database.property-store/property-store
   #'genegraph.transform.clinvar.cancervariants/vicc-db
   #'genegraph.sink.event-recorder/event-database))

(defn eventify [input-map]
  ;; Mostly replicating
  ;; (map #(stream/consumer-record-to-clj % :clinvar-raw))
  ;; but without some fields that are not used here
  {::ann/format :clinvar-raw
   :genegraph.sink.event/key nil
   :genegraph.sink.event/value (json/generate-string input-map)
   ::topic "clinvar-raw"
   ::partition 0})

(def topic-file "clinvar-raw-local-filtered-vcepvars.txt")

(defn get-message-seq []
  (-> topic-file
      io/reader
      line-seq
      (->> (map #(json/parse-string % true)))))

(defn message-proccess!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ; needed for add-to-db! to work
      ann/add-action
      xform-types/add-data
      xform-types/add-model
      add-to-db!
      #_(#(xform-types/add-model %))))

(defn process-topic-file [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (map message-proccess! messages)))


(comment
  (-> "trait-test-input.txt"
      io/reader
      line-seq
      first
      (json/parse-string true)
      eventify
      genegraph.transform.clinvar.core/add-parsed-value
      ca/add-data-for-trait
      (genegraph.transform.clinvar.core/add-model-from-contextualized-data)
      :genegraph.database.query/model))
