(ns genegraph.transform.clinvar.ga4gh
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [com.climate.claypoole :as cp]
            [genegraph.annotate :as ann]
            [genegraph.database.util :refer [tx write-tx]]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.server]
            [genegraph.sink.document-store :as docstore]
            [genegraph.sink.event :as ev]
            [genegraph.source.registry.rocks-registry :as rocks-registry]
            [genegraph.transform.clinvar.clinical-assertion :as ca]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.iri :refer [ns-cg]]
            [genegraph.transform.clinvar.submitter :as submitter]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.clinvar.variation :as variation]
            [genegraph.transform.types :as xform-types]
            [genegraph.util.fs :refer [gzip-file-reader]]
            [io.pedestal.log :as log]
            [mount.core :as mount])
  (:import (java.time Instant Duration)))

(def stop-removing-unused [#'write-tx #'tx #'pprint #'util/parse-nested-content])

(defn start-states! []
  (mount/start
   #'genegraph.database.instance/db
   #'genegraph.database.property-store/property-store
   #'genegraph.transform.clinvar.cancervariants/cache-db
   #'genegraph.transform.clinvar.variation/variation-data-db
   #'genegraph.sink.event-recorder/event-database
   #'genegraph.sink.document-store/db
   #'genegraph.transform.clinvar.variation/variation-data-db
   #'genegraph.transform.clinvar.submitter/submitter-data-db
   #'genegraph.transform.clinvar.clinical-assertion/trait-data-db
   #'genegraph.transform.clinvar.clinical-assertion/trait-set-data-db
   #'genegraph.transform.clinvar.clinical-assertion/clinical-assertion-data-db
   #'rocks-registry/db
   #'rocks-registry/server))

(defn eventify [input-map]
  ;; Mostly replicating
  ;; (map #(stream/consumer-record-to-event % :clinvar-raw))
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

(defn add-data-catch-exceptions [event]
  (try
    (xform-types/add-data event)
    (catch Exception ex
      (log/error :fn :add-data-catch-exceptions
                 :msg "Exception adding data to event"
                 :event event
                 :exception ex)
      event)))

(defn message-proccess-with-jena!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ann/add-action
      add-data-catch-exceptions
      xform-types/add-model
      docstore/store-document-raw-key
      ev/add-to-db!))

(defn message-proccess-with-rocksdb!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ann/add-action
      add-data-catch-exceptions
      docstore/store-document-raw-key))

(defn message-proccess-no-db!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ; needed for add-to-db! to work
      ann/add-action
      add-data-catch-exceptions))


(defn clinvar-add-iri [event]
  ;; TODO doesn't work for types without ids
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        content (:content message)]
    (assoc event :genegraph.annotate/iri
           (str (ns-cg (:entity_type content))
                "_" (:id content)
                "." (:release_date message)))))

(defn process-topic-file-parallel-no-output [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (write-tx
     (doseq [event (->> messages
                        (pmap message-proccess-with-rocksdb!))]
       (let [clinvar-type (:genegraph.transform.clinvar/format event)])))))

(defn process-topic-file [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (with-open [statement-writer (io/writer (str input-filename "-output-statements"))
                variation-descriptor-writer (io/writer (str input-filename "-output-variation-descriptors"))
                other-writer (io/writer (str input-filename "-output-other"))]
      (write-tx
       (doseq [event (->> (map message-proccess-with-jena! messages)
                          #_(take 10))]
         (let [clinvar-type (:genegraph.transform.clinvar/format event)
               ;;_ (log/info :clinvar-type clinvar-type)
               ;;_ (log/info :event event)
               writer (case clinvar-type
                        :clinical_assertion statement-writer
                        :variation variation-descriptor-writer
                        other-writer)]
           (.write writer (-> event
                              :genegraph.annotate/data-contextualized
                              (dissoc "@context")
                              common/map-remove-nil-values
                              (json/generate-string)))
           (.write writer "\n")))))))

(defn slashjoin [& args]
  (reduce (fn [agg val]
            (str agg "/" val))
          args))

(defn cv-transform-test-fname [& relative-path-segs]
  (str "test/genegraph/transform/clinvar/test-inputs/" (apply slashjoin relative-path-segs)))

(defn not-deleted?
  "Returns true if .record_metadata.deleted is not truthy"
  [[entry-k entry-v]]
  (let [deleted? (get-in entry-v [:record_metadata :deleted])]
    (when deleted? (log/info :entry-id (:id entry-v) :deleted deleted?))
    (not deleted?)))

;; TODO
;; One idea is to extend the lazy seqs returned from rocksdb.clj to make them implement java Closeable
;; so caller can close them instead of needing to construct an iterator directly in order to close it
#_(reify
    java.lang.AutoCloseable
    (close [this] (.close iter))

    clojure.lang.ISeq
    (first [this] (.first out))
    (next [this] (.next out))
    (more [this] (.more out))
    (cons [this o] (.cons out o)))

(defn latest-versions-seq-all
  "For a RocksDB instance with keys and values structured in ways we know how
   to iterate (<prefix><id>.<version>), return the latest versions.
   Assumes versions are lexicographically sortable.
   Relies on RocksDB itself being sorted on the byte array keys.

   If :filter-deleted is true, records are filtered out when the latest
   version contains .release_metadata{.deleted=true}.

   If an iterator is not passed in, opens one and returns it. Caller must close this ASAP"
  ([iter] (latest-versions-seq-all iter {}))
  ([iter {:keys [filter-deleted] :or {filter-deleted true} :as opts}]
   (when (.isValid iter)
     (->> (for [[entry-k entry-v] (rocksdb/rocks-entry-iterator-seq iter)]
            (let [unversioned-iri (get-in entry-v [:record_metadata :is_version_of])]
              [unversioned-iri entry-v]))
          (partition-by first)
          (map last)
                ;; If :filter-deleted is true, remove deleted
          (filter #(or (not filter-deleted)
                       (not-deleted? %)))))))

(comment
  "trying latest versions seq iter-based"

  ;; variation data entries: 640402
  ;; count written to file:  628470

  (time
   (do (with-open [iter (rocksdb/entire-db-iter genegraph.transform.clinvar.variation/variation-data-db)
                   out-writer (io/writer "variations-test.txt")]
         (log/info :iter iter)
         (doseq [[k v] (->> (latest-versions-seq-all iter))]
           (.write out-writer (prn-str k))))
       (genegraph.rocksdb/mem-stats genegraph.transform.clinvar.variation/variation-data-db)))

  ())


(defn snapshot-variation-db-rocksdb []
  (let [db genegraph.transform.clinvar.variation/variation-data-db
        out-fname "variation-data-db-snapshot-rocksdb.ndjson"]
    (with-open [iter (rocksdb/entire-db-iter db)
                writer (io/writer out-fname)]
      (doseq [[entry-k entry-v] (latest-versions-seq-all iter)]
        (.write writer (json/generate-string entry-v))
        (.write writer "\n")))))

(defn snapshot-statements-db-rocksdb []
  (let [db ca/clinical-assertion-data-db
        out-fname "statements-data-db-snapshot.ndjson"]
    (with-open [iter (rocksdb/entire-db-iter db)
                writer (io/writer (io/file out-fname))]
      (doseq [[entry-k entry-v] (latest-versions-seq-all iter)]
        (.write writer (json/generate-string entry-v))
        (.write writer "\n")))))

(comment
  (start-states!)

  (process-topic-file (cv-transform-test-fname "relative-cnv/cvraw-kinda-long-dup1.txt"))

  (process-topic-file "data/cg-vcep-2019-07-01/one_variant.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/variation.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/trait.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/trait_set.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/clinical_assertion.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/one-scv.txt")

  (start-states!)
  (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/variation-556853.txt")
  (do (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/variation.txt")
      (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/trait.txt")
      (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/trait_set.txt")
      (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/clinical_assertion.txt"))

  (snapshot-statements-db-rocksdb))

(comment
  (def assertions
    (-> "cg-vcep-2019-07-01/clinical_assertion.txt"
        io/reader
        line-seq
        (->>
         (map #(json/parse-string % true))
         (filter #(= "SCV000852165" (get-in % [:content :id])))
         (map message-proccess-with-rocksdb!)
         (map docstore/store-document-raw-key)
         (map #(docstore/get-document-raw-key ca/clinical-assertion-data-db (:genegraph.annotate/data-id %)))
         #_(map :genegraph.annotate/data)))))

(comment
  "Testing delete operation"
  (start-states!)
  (rocksdb/rocks-destroy-state! #'genegraph.transform.clinvar.variation/variation-data-db)
  (process-topic-file (cv-transform-test-fname "one-variation-create-update-delete"
                                               "clinvar-raw-variation-36823-deleted.txt"))
  ;; last event in that file is a delete of the same id in prior events, so no data should remain
  (let [db genegraph.transform.clinvar.variation/variation-data-db]
    (assert (= 0 (count (take 100 (rocksdb/entire-db-entry-seq db)))))))

(comment
  (-> "statements.txt"
      io/reader
      line-seq
      (->> (map #(json/parse-string % true))
           (map common/map-compact-namespaced-values)
           ((fn [records]
              (with-open [writer (io/writer "statements-compacted.txt")]
                (doseq [rec records]
                  (.write writer (json/generate-string rec))
                  (.write writer "\n"))))))))

(defn run-full-topic-file []
  (let [input-filename "clinvar-raw.gz"
        messages (map #(json/parse-string % true) (line-seq (gzip-file-reader input-filename)))]
    (map message-proccess-with-jena! messages)))


(defn load-file
  ([filename] (load-file filename Long/MAX_VALUE))
  ([filename limit]
   (-> filename
       io/reader
       line-seq
       (->> (take limit)
            (map #(json/parse-string % true))
            (map message-proccess-with-rocksdb!)))))

(defonce thread-pool (cp/threadpool 20))

(defn load-file-parallel
  ([filename] (load-file-parallel filename Long/MAX_VALUE))
  ([filename limit]
   (-> filename
       io/reader
       line-seq
       (->> (take limit)
            (map #(json/parse-string % true))
            ((fn [records]
               (cp/pmap
                thread-pool
                (fn [[i record]]
                  (let [start (Instant/now)
                        event (message-proccess-with-rocksdb! record)
                        end (Instant/now)
                        dur (Duration/between start end)]
                    (when (< 0 (.compareTo dur (Duration/ofMillis 200)))
                      (log/warn :msg "process-event took longer than 200 milliseconds"
                                :duration (str dur)
                                :event-data (:genegraph.annotate/data event)))
                    (when (= 0 (rem i 1000))
                      (log/info :progress i))
                    event))
                (map-indexed vector records))))))))

(comment
  (mount/start #'genegraph.repl-server/nrepl-server)
  (time
   (->> (load-file-parallel
         "/Users/kferrite/dev/clinvar-streams/clinvar-raw-2023-04-10_variation.txt")
        count))

  (time
   (->> (load-file
         "/Users/kferrite/dev/clinvar-streams/clinvar-raw-2023-04-10_trait.txt")
        count))

  (time
   (->> (load-file
         "/Users/kferrite/dev/clinvar-streams/clinvar-raw-2023-04-10_trait_set.txt")
        count))

  (time
   (->> (load-file
         "/Users/kferrite/dev/clinvar-streams/clinvar-raw-2023-04-10_submitter.txt")
        count))

  (time
   (->> (load-file-parallel
         "/Users/kferrite/dev/clinvar-streams/clinvar-raw-2023-04-10_clinical_assertion.txt")
        count))

  (time
   (->> (load-file-parallel
         "/Users/kferrite/dev/genegraph/SCV000050605.json")
        (map :genegraph.annotate/data)
        (json/generate-string)
        (spit "SCV000050605-transformed.json")))


  (time
   (->
    #_"/Users/kferrite/dev/clinvar-streams/clinvar-raw-2023-02-08_submitter.txt"
    "/Users/kferrite/dev/clinvar-streams/clinvar-raw-2023-04-10_clinical_assertion.txt"
    io/reader
    line-seq
    (->> (take 1000)
         (map #(json/parse-string % true))
         (map message-proccess-with-rocksdb!)
         count))))
