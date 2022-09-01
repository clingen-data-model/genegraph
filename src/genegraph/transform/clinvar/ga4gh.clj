(ns genegraph.transform.clinvar.ga4gh
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [genegraph.annotate :as ann]
            [genegraph.sink.event :refer [add-to-db!]]
            [genegraph.database.util :refer [write-tx]]
            [genegraph.transform.clinvar.clinical-assertion :as ca]
            [genegraph.transform.types :as xform-types]
            [genegraph.util.fs :refer [gzip-file-reader]]
            [io.pedestal.log :as log]
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
      ((fn [e] (try
                 (xform-types/add-data e)
                 (catch Exception ex
                   (log/error :fn :message-process!
                              :msg "Exception adding data to event"
                              :event e
                              :exception ex))
                 (finally e))))
      (#(xform-types/add-model %))
      ((fn [e] (when (:genegraph.database.query/model e) (add-to-db! e)) e))))

(defn process-topic-file [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))
        ;; map of :genegraph.transform.clinvar/format to the writer
        writers (atom {})]
    (with-open [statement-writer (io/writer (str input-filename "-output-statements"))
                variation-descriptor-writer (io/writer (str input-filename "-output-variation-descriptors"))
                other-writer (io/writer (str input-filename "-output-other"))]
      (doseq [event (map message-proccess! messages)]
        (let [clinvar-type (:genegraph.transform.clinvar/format event)
              writer (case clinvar-type
                       :clinical_assertion statement-writer
                       :variation variation-descriptor-writer
                       other-writer)]
          (.write writer (-> event
                             :genegraph.annotate/data-contextualized
                             (dissoc "@context")
                             (json/generate-string)))
          (.write writer "\n"))))))


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

(defn run-full-topic-file []
  (let [input-filename "clinvar-raw.gz"
        messages (map #(json/parse-string % true) (line-seq (gzip-file-reader input-filename)))]
    (map message-proccess! messages)))
