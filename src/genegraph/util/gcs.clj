(ns genegraph.util.gcs
  (:require [genegraph.env :as env]
            [clojure.java.io :as io]
            [io.pedestal.log :as log]
            [genegraph.util.fs :as fs])
  (:import [java.time ZonedDateTime ZoneOffset]
           java.time.format.DateTimeFormatter
           [java.nio ByteBuffer]
           [java.nio.file Path Paths]
           [java.io InputStream OutputStream FileInputStream File]
           [com.google.common.io ByteStreams]
           [com.google.cloud.storage Bucket BucketInfo Storage StorageOptions
            BlobId BlobInfo Blob]
           [com.google.cloud.storage Storage$BlobWriteOption
            Storage$BlobTargetOption
            Storage$BlobSourceOption
            Storage$BlobListOption
            Blob$BlobSourceOption]))

(defn- storage []
  (.getService (StorageOptions/getDefaultInstance)))

(defn put-file-in-bucket!
  ([source-file blob-name]
   (put-file-in-bucket! source-file blob-name {:content-type "application/gzip"}))
  ([source-file blob-name options]
   (let [blob-id (BlobId/of env/genegraph-bucket blob-name)
         blob-info (-> blob-id BlobInfo/newBuilder (.setContentType (:content-type options)) .build)
         from (.getChannel (FileInputStream. source-file))]
     (with-open [to (.writer (storage) blob-info (make-array Storage$BlobWriteOption 0))]
       (ByteStreams/copy from to)))))

(defn get-file-from-bucket!
  [source-blob target-file]
  (let [target-path (Paths/get target-file
                               (make-array java.lang.String 0))
        blob (.get (storage)
                   (BlobId/of env/genegraph-bucket source-blob))]
    (.downloadTo blob target-path)))

(defn list-items-in-bucket
  ([] (list-items-in-bucket nil))
  ([prefix]
   (let [options (if prefix
                   (into-array [(Storage$BlobListOption/prefix prefix)])
                   (make-array Storage$BlobListOption 0))]
     (-> (.list (storage)
                env/genegraph-bucket
                options)
         .iterateAll
         .iterator
         iterator-seq))))

(defn get-files-with-prefix!
  "Store all files in bucket matching PREFIX to TARGET-DIR"
  [prefix target-dir]
  (if (fs/ensure-target-directory-exists! target-dir)
    (doseq [blob (list-items-in-bucket prefix)]
      (let [target-path (Paths/get (str target-dir
                                        (re-find #"/.*$" (.getName blob)))
                                   (make-array java.lang.String 0))]
        (.downloadTo blob target-path)))
    (log/error :fn ::get-files-with-prefix!
               :msg "Could not create directory"
               :path target-dir)))

(defn push-directory-to-bucket!
  "Copy all files from SOURCE-DIR to TARGET-DIR in bucket."
  [source-dir target-dir]
  (let [dir (io/file source-dir)]
    (doseq [file (.listFiles dir)]
      (put-file-in-bucket! file (str target-dir "/" (.getName file))))))
