(ns genegraph.migration
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [genegraph.env :as env]
            [me.raynes.fs :as fs]
            [genegraph.sink.base :as base]
            [genegraph.database.instance :as db]
            [genegraph.sink.stream :as stream]
            [genegraph.suggest.suggesters :as suggest]
            [mount.core :refer [start stop]]
            [clojure.java.shell :refer [sh]])
  (:import [java.time ZonedDateTime ZoneOffset]
           java.time.format.DateTimeFormatter
           [java.nio ByteBuffer]
           [java.nio.file Path Paths]
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
  (println "Building database at " path)
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
    (suggest/build-all-suggestions)
    (stop #'db/db)))

(defn compress-database
  "Construct a tarball out of the given database"
  [source-dir target-archive]
  (println "Compressing database at " source-dir " to " target-archive)
  (let [result (sh "tar" "-czf" target-archive "-C" source-dir ".")]
    (if (= 0 (:exit result))
      true
      false)))

(defn send-database
  [target-bucket database-archive database-version]
  (println "Sending " database-archive " to " target-bucket " with version " database-version)
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
    (Thread/sleep 1000) ;; seems to be a race condition here to avoid
    (send-database env/genegraph-bucket archive-path version-id)))

(defn retrieve-migration
  "Pull the specified migration from cloud storage."
  [bucket blob-name target-dir]
  (let [target-path (Paths/get (str target-dir "/" blob-name)
                               (make-array java.lang.String 0))
        blob (.get (.getService (StorageOptions/getDefaultInstance))
                   (BlobId/of bucket blob-name))]
    (fs/mkdirs target-dir)
    (.downloadTo blob target-path)))

(defn decompress-database
  "After database has been downloaded, extract the tarball"
  [target-dir archive-path]
  (let [result (sh "tar" "-xzf" archive-path "-C" target-dir)]
    (println result)
    (if (= 0 (:exit result))
      true
      false)))

(defn populate-data-vol-if-needed
  "Check to see if a copy of the data already exists, if not, download and decompress the
  database."
  []
  (when-not (.exists (io/file env/data-vol "tdb"))
    (let [archive-file (str env/data-version ".tar.gz")
          archive-path (str env/data-vol "/" archive-file)]
      (fs/mkdirs env/data-vol)
      (println "retrieving " archive-file)
      (retrieve-migration env/genegraph-bucket archive-file env/data-vol)
      (println "decompressing " archive-path)
      (decompress-database env/data-vol archive-path))))


