(ns genegraph.migration
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [genegraph.env :as env]
            [me.raynes.fs :as fs]
            [genegraph.sink.base :as base]
            [genegraph.database.instance :as db]
            [genegraph.sink.event :as event]
            [genegraph.sink.stream :as stream]
            [genegraph.suggest.suggesters :as suggest]
            [genegraph.sink.batch :as batch]
            [genegraph.source.graphql.core :as core]
            [genegraph.source.graphql.common.cache :as cache]
            [mount.core :refer [start stop]]
            [clojure.java.shell :refer [sh]]
            [io.pedestal.log :as log])
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

(defn warm-resolver-cache []
  (when env/use-gql-cache
    (let [gql-file-names (-> "resolver-cache-warm.edn" io/resource slurp edn/read-string)]
      (log/info :fn :warm-resolver-cache :msg "Warming the resolver cache..." :resources gql-file-names)
      (doall (pmap (fn [query-file] 
                     (let [results (-> query-file io/resource slurp core/gql-query)]
                       (when-let [errors (:errors results)]
                         (log/error :fn :warm-resolver-cache
                                    :msg (str "Resolver cache warmer script has errors: " query-file)
                                    :errors errors)))
                     (log/debug :fn :warm-resolver-cache :msg (str query-file " complete.")))  gql-file-names))
      (log/info :fn :warm-resolver-cache :msg "Warming the resolver cache...complete."))))

(defn build-base-database
  "Build the database with base data only (no curations from streaming service"
  [path]
  (with-redefs [env/data-vol path]
    (fs/mkdirs env/data-vol)
    (start #'db/db)
    (base/initialize-db!)
    ;; (batch/process-batched-events!)
    (start #'suggest/suggestions)
    (suggest/build-all-suggestions)
    (stop #'suggest/suggestions)
    (stop #'db/db)))

(defn build-database
  "Build the Jena database and associated indexes from scratch."
  [path]
  (log/info :fn :build-database :msg (str "Building database at " path))
  (with-redefs [env/data-vol path]
    (fs/mkdirs env/data-vol)
    (start #'db/db)
    (base/initialize-db!)
    (batch/process-batched-events!)
    (start #'stream/consumer-thread)
    (log/info :fn :build-database :msg "Processing streams...")
    (stream/wait-for-topics-up-to-date)
    (log/info :fn :build-database :msg "Stopping streams...")
    (stop #'stream/consumer-thread)
    (log/info :fn :build-database :msg "Waiting for streams to close...")
    (stream/wait-for-topics-closed)
    (log/info :fn :build-database :msg "Starting resolver cache...")
    (start #'cache/resolver-cache-db)
    (warm-resolver-cache)
    (stop #'cache/resolver-cache-db)
    (start #'suggest/suggestions)
    (log/info :fn :build-database :msg "Building suggesters...")
    (suggest/build-all-suggestions)
    (stop #'suggest/suggestions)
    (stop #'db/db)))

(defn compress-database
  "Construct a tarball out of the given database"
  [source-dir target-archive]
  (log/info :fn :compress-database :msg (str "Compressing database at " source-dir " to " target-archive))
  (let [result (sh "tar" "-czf" target-archive "-C" source-dir ".")]
    (if (= 0 (:exit result))
      true
      false)))

(defn send-database
  [target-bucket database-archive database-version]
  (log/info :fn :send-database :msg (str "Sending " database-archive " to " target-bucket " with version " database-version))
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

(defn create-local-base-migration
  "Populate a new database with just local data, not intended for Google Cloud"
  []
  (let [version-id (new-version-identifier)
        database-path (str env/base-dir "/" version-id)]
    (build-base-database database-path)))

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
    (log/info :fn :decompress-database :msg result)
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
      (log/info :fn :populate-data-vol-if-needed :msg (str "retrieving " archive-file))
      (retrieve-migration env/genegraph-bucket archive-file env/data-vol)
      (decompress-database env/data-vol archive-path))))


