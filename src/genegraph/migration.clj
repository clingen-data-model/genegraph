(ns genegraph.migration
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [genegraph.env :as env]
            [me.raynes.fs :as fs]
            [genegraph.sink.base :as base]
            [genegraph.database.instance :as db]
            [genegraph.sink.stream :as stream]
            [mount.core :refer [start stop]]
            [clojure.java.shell :refer [sh]])
  (:import [java.time ZonedDateTime ZoneOffset]
           java.time.format.DateTimeFormatter
           [java.nio ByteBuffer]
           [java.io InputStream OutputStream FileInputStream File]
           [com.google.common.io ByteStreams]
           [org.apache.kafka.clients.producer Producer KafkaProducer ProducerRecord]
           [com.google.cloud.storage Bucket BucketInfo Storage StorageOptions
            BlobId BlobInfo Blob]
           [com.google.cloud.storage Storage$BlobWriteOption
            Storage$BlobTargetOption
            Storage$BlobSourceOption
            Blob$BlobSourceOption]))

(defn- new-version-identifier
  "Generate a new identifier for a migration"
  []
  (.format (ZonedDateTime/now ZoneOffset/UTC) 
           (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HHmm")))

(defn build-database
  "Build the Jena database and associated indexes from scratch."
  [path]
  (with-redefs [env/data-vol path]
    (fs/mkdirs env/data-vol)
    (start #'db/db)
    (base/initialize-db!)
    (start #'stream/consumer-thread)
    ;; Address race where all threads are "up to date", needs better fix
    (Thread/sleep (* 1000 60 2))
    (while (not (stream/up-to-date?))
      (Thread/sleep (* 1000 10)))
    (stop #'stream/consumer-thread)
    (while (not (stream/consumers-closed?))
      (Thread/sleep 1000))
    (stop #'db/db)))

(defn compress-database
  "Construct a tarball out of the given database"
  [source-dir target-archive]
  (sh "tar" "-czf" (str target-archive ".tar.gz") "-C" source-dir "."))

(defn send-database
  [target-bucket database-archive database-version]
  (let [gc-storage (.getService (StorageOptions/getDefaultInstance))
        blob-id (BlobId/of target-bucket (str database-version ".tar.gz"))
        blob-info (-> blob-id BlobInfo/newBuilder (.setContentType "application/gzip") .build)
        from (.getChannel (FileInputStream. database-archive))]
    (with-open [to (.writer gc-storage blob-info (make-array Storage$BlobWriteOption 0))]
      (ByteStreams/copy from to))))

(defn create-migration
  "Populate a new database, package and upload to Google Cloud"
  []
  (let [version-id (new-version-identifier)
        database-path (str env/base-dir "/" version-id)
        archive-path (str database-path ".tar.gz")]
    (build-database database-path)
    (compress-database database-path archive-path)
    (send-database env/genegraph-bucket archive-path version-id))

  ;; create directory
  ;; build database
  ;; unmount database
  ;; create tarball
  ;; send to GCS
  )
