(ns genegraph.sink.document-store
  (:require [genegraph.rocksdb :as rocks]
            [clojure.string :as s]
            [cheshire.core :as json]
            [mount.core :as mount :refer [defstate]]))

(defstate db
  :start (rocks/open "document_store")
  :stop (rocks/close db))

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

(defn add-data
  "Associate the deserialized form of the event with the key ::data.

  For now, assumes all event payloads are json encoded strings.
  Will update when we see a payload of a different kind."
  [event]
  (assoc event
         ::data
         (json/parse-string
          (:genegraph.sink.event/value event)
          true)))

(def add-data-interceptor
  {:name ::add-data
   :enter add-data})

;; Thoughts
;; 1) this should really be the IRI of the entity; should not depend at all on 
;; 2) i'm not entirely sure I want to rely on events being in an ordered sequence
;; to find the most recent instance of something prior-to a given date
;; (but if I don't do it this way, how?)

(defn add-id
  "For now, assuming just events in clinvar-raw. Will need to adapt
  for other sorts of events later."
  [event]
  (assoc event
         ::id 
         (s/join "|"
                 [(:genegraph.annotate/format event)
                  (get-in event [::data :content :entity_type])
                  (get-in event [::data :content :id])
                  (get-in event [::data :content :date_last_updated])])))

(def add-id-interceptor
  {:name ::add-id
   :enter add-id})

(def storeable-event-types
  #{"update" "create"})

(defn add-is-storeable
  "Probably only want to store create and update events
  may want to consider deleting other sorts of events in future.

  Again, just doing clinvar-raw at the moment"
  [event]
  (if (storeable-event-types (get-in event [::data :event_type]))
    (assoc event ::storeable? true)
    event))

(def add-is-storeable-interceptor
  {:name ::add-is-storeable
   :enter add-is-storeable})

(defn store-document
  "Store the document data associated with this event"
  [event]
  (when (and (:genegraph.annotate/data event) (::id event))
    (rocks/rocks-put-raw-key!
     (or (::db event) db)
     (.getBytes (::id event))
     (:genegraph.annotate/data event)))
  event)

(def store-document-interceptor
  {:name ::store-document
   :enter store-document})

(defn get-document
  ([id] (get-document db id))
  ([db id] (rocks/rocks-get-raw-key db (.getBytes id))))

(defn get-documents-by-prefix [event]
  (let [db (or (::db event) db)]
    (assoc event
           ::documents-by-prefix
           (reduce (fn [m prefix]
                     (assoc m prefix (rocks/raw-prefix-seq
                                      db
                                      (.getBytes prefix))))
                   {}
                   (::get-documents-by-prefix event)))))
