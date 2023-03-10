(ns genegraph.sink.document-store
  (:require [genegraph.rocksdb :as rocks]
            [clojure.string :as s]
            [cheshire.core :as json]
            [mount.core :as mount :refer [defstate]]
            [taoensso.nippy :as nippy]))

(defstate db
  :start (rocks/open "document_store")
  :stop (rocks/close db))

(defn store-event
  "Store the document data in :genegraph.annotate/data
  with the key :genegraph.annotate/data-id in the user opened
  database in :genegraph.annotate/data-db or in the default db."
  [event]
  (when (:genegraph.annotate/data-id event)
    (rocks/rocks-put-raw-key!
     (or (:genegraph.annotate/data-db event) db)
     (nippy/fast-freeze (:genegraph.annotate/data-id event))
     ;; TODO- TON -Maybe better as a reference to db or a
     ;; function returning db rather than dissoc??
     (dissoc event :genegraph.annotate/data-db)))
  event)

(defn store-document
  "Store the document data in :genegraph.annotate/data
  with the key :genegraph.annotate/data-id in the user opened
  database in :genegraph.annotate/data-db or in the default db."
  [event]
  (when (and (:genegraph.annotate/data event) (:genegraph.annotate/data-id event))
    (rocks/rocks-put-raw-key!
     (or (:genegraph.annotate/data-db event) db)
     (nippy/fast-freeze (:genegraph.annotate/data-id event))
     (:genegraph.annotate/data event)))
  event)

(def store-document-interceptor
  {:name ::store-document
   :enter store-document})

(defn get-document
  ([id] (get-document db id))
  ([db id] (rocks/rocks-get-raw-key db (nippy/fast-freeze id))))

(defn get-documents-by-prefix [event]
  (let [db (or (:genegraph.annotate/data-db event) db)]
    (assoc event
           ::documents-by-prefix
           (reduce (fn [m prefix]
                     (assoc m prefix (rocks/raw-prefix-seq
                                      db
                                      (nippy/fast-freeze prefix))))
                   {}
                   (::get-documents-by-prefix event)))))

(def an-event
  {:genegraph.annotate/format :clinvar-raw,
   :genegraph.sink.event/key
   "clinical_assertion_SCV000028756_2019-07-01",
   :genegraph.sink.event/value
   "{\"release_date\":\"2019-07-01\",\"event_type\":\"create\",\"content\":{\"variation_id\":\"8081\",\"entity_type\":\"clinical_assertion\",\"variation_archive_id\":\"VCV000008081\",\"submitter_id\":\"3\",\"date_last_updated\":\"2019-03-31\",\"interpretation_comments\":[],\"interpretation_description\":\"Pathogenic\",\"trait_set_id\":\"2199\",\"internal_id\":\"28756\",\"clingen_version\":0,\"submission_id\":\"3.2010-12-30\",\"local_key\":\"601545.0009_SUBCORTICAL LAMINAR HETEROTOPIA\",\"clinical_assertion_observation_ids\":[\"SCV000028756.0\"],\"title\":\"PAFAH1B1, ARG8TER_SUBCORTICAL LAMINAR HETEROTOPIA\",\"assertion_type\":\"variation to disease\",\"rcv_accession_id\":\"RCV000008548\",\"clinical_assertion_trait_set_id\":\"SCV000028756\",\"id\":\"SCV000028756\",\"submission_names\":[],\"record_status\":\"current\",\"date_created\":\"2011-01-25\",\"review_status\":\"no assertion criteria provided\",\"interpretation_date_last_evaluated\":\"2003-10-28\",\"version\":\"1\"}}",
   :genegraph.sink.stream/timestamp 1657141148711,
   :genegraph.sink.stream/topic "clinvar-raw-testdata_20220523",
   :genegraph.sink.stream/partition 0,
   :genegraph.sink.stream/offset 29751,
   :genegraph.annotate/producer-topic :test-public-v1})

(def doc-store-event
  (assoc an-event :genegraph.annotate/data (:genegraph.sink.event/value an-event)
         :genegraph.annotate/data-id (:genegraph.sink.event/key an-event)))

(comment
  (store-document doc-store-event)
  (get-document "clinical_assertion_SCV000028756_2019-07-01"))
