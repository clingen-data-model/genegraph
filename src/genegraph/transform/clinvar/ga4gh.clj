(ns genegraph.transform.clinvar.ga4gh
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [genegraph.annotate :as ann]
            [genegraph.database.names :refer [prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.database.util :refer [write-tx tx]]
            [genegraph.sink.event :as event]
            [genegraph.transform.clinvar.clinical-assertion :as ca]
            [genegraph.transform.clinvar.core :refer [add-parsed-value]]
            [genegraph.transform.clinvar.iri :refer [ns-cg]]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.transform.types :as xform-types]
            [genegraph.util.fs :refer [gzip-file-reader]]
            [io.pedestal.log :as log]
            [mount.core :as mount]))

(def stop-removing-unused [#'write-tx #'tx #'pprint])

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
  {:genegraph.annotate/format :clinvar-raw
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

(defn message-proccess-parallel!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [messages]
  (->> messages
       (map #(-> %
                 eventify
                 ann/add-metadata
                 ann/add-action))
       ((fn [msgs]
          (flatten
           (map (fn [batch]
                  (log/info :batch-size (count batch)
                            :first (first batch)
                            :last (last batch))

                  (pmap (fn [e]
                          (try
                            (xform-types/add-data e)
                            (catch Exception ex
                              (log/error :fn :message-process!
                                         :msg "Exception adding data to event"
                                         :event e
                                         :exception (prn-str ex))
                              e)))
                        batch))
               ;; Batch the input seq into
                (partition-by #(vector [(get-in % [:release_date])
                                        (get-in % [:content :entity_type])
                                        (get-in % [:content :subclass_type])])
                              msgs)))))
       (map (fn [e] (if (:genegraph.annotate/data e) (xform-types/add-model e) e)))
       (map (fn [e] (if (:genegraph.database.query/model e) (event/add-to-db! e) e)))))

(defn process-topic-file-parallel [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (with-open [statement-writer (io/writer (str input-filename "-output-statements"))
                variation-descriptor-writer (io/writer (str input-filename "-output-variation-descriptors"))
                other-writer (io/writer (str input-filename "-output-other"))]
      (doseq [event (message-proccess-parallel! messages)]
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
                              :exception ex)
                   e))))
      ((fn [e] (if (:genegraph.annotate/data e) (xform-types/add-model e) e)))
      ((fn [e] (if (:genegraph.database.query/model e) (event/add-to-db! e) e)))))

(defn clinvar-add-iri [event]
  ;; TODO doesn't work for types without ids
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        content (:content message)]
    (assoc event :genegraph.annotate/iri
           (str (ns-cg (:entity_type content))
                "_" (:id content)
                "." (:release_date message)))))

(defn add-data-no-transform
  [event]
  (-> (add-parsed-value event)
      ((fn [e] (assoc e :genegraph.annotate/data
                      (-> (:genegraph.transform.clinvar.core/parsed-value e)
                          (#(merge % (:content %)))
                          (dissoc :content)))))
      ((fn [e] (assoc e :genegraph.annotate/data-contextualized
                      (merge (:genegraph.annotate/data e)
                             {"@context" {"@vocab" (str (get prefix-ns-map "cgterms"))}}))))
      clinvar-add-iri))

(defn add-model [event]
  (if (:genegraph.annotate/data event)
    (xform-types/add-model event)
    event))

(defn add-to-db! [event]
  (if (:genegraph.database.query/model event)
    (event/add-to-db! event)
    event))

(defn add-jsonld [event]
  (assoc event
         :genegraph.annotate/jsonld
         (jsonld/model-to-jsonld (:genegraph.database.query/model event))))

(defn process-topic-file [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (with-open [statement-writer (io/writer (str input-filename "-output-statements"))
                variation-descriptor-writer (io/writer (str input-filename "-output-variation-descriptors"))
                other-writer (io/writer (str input-filename "-output-other"))]
      (write-tx
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
           (.write writer "\n")))))))



(defn message-process-no-transform!
  [message]
  (-> message
      eventify
      ann/add-metadata
      ann/add-action
      add-data-no-transform
      add-model
      #_add-jsonld
      add-to-db!))

(defn ingest-file-no-transform [input-filename]
  (let [messages (-> input-filename
                     io/reader
                     line-seq
                     (->> #_(take 10)
                      (map #(json/parse-string % true))))]
    (doseq [event (map message-process-no-transform! messages)]
      (log/info :fn :ingest-file-no-transform
                ;;:event event
                :iri (:genegraph.annotate/iri event)
                #_#_:model (:genegraph.database.query/model event)))))


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

(defn dump-after-loading []
  (let [scv-rs (q/select "select distinct ?vof where {
                          ?i a :vrs/VariationGermlinePathogenicityStatement .
                          ?i :dc/is-version-of ?vof . }")]))
