(ns genegraph.util.gcs
  (:require [genegraph.env :as env]
            [clojure.java.io :as io]
            [io.pedestal.log :as log])
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
                                     Blob$BlobSourceOption]
           (com.google.cloud WriteChannel)
           (java.nio.channels WritableByteChannel)
           (java.nio.charset StandardCharsets)))


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

(defn ^WriteChannel get-bucket-write-channel
  "Returns a function which when called returns an open WriteChannel to blob-name within env/genegraph-bucket"
  ([^String blob-name]
   (get-bucket-write-channel env/genegraph-bucket blob-name))
  ([^String bucket-name ^String blob-name]
   (let [gc-storage (.getService (StorageOptions/getDefaultInstance))
         blob-id (BlobId/of bucket-name blob-name)
         blob-info (-> blob-id BlobInfo/newBuilder (.setContentType (:content-type "application/gzip")) .build)]
     (fn [] (.writer gc-storage blob-info (make-array Storage$BlobWriteOption 0))))))

(defn channel-write-string!
  "Write a string in UTF-8 to a WriteableByteChannel.
  Returns the channel for use in threading."
  [^WritableByteChannel channel ^String input-string]
  (.write channel (ByteBuffer/wrap (.getBytes input-string StandardCharsets/UTF_8)))
  channel)

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

(defn ensure-target-directory-exists!
  "Create directory at PATH if it does not already exist.
  Return false if directory cannot be created or already
  exists as something other than a directory."
  [path]
  (let [dir (io/file path)]
    (if (and (.exists dir) (.isDirectory dir))
      true
      (.mkdir dir))))

(defn get-files-with-prefix!
  "Store all files in bucket matching PREFIX to TARGET-DIR"
  [prefix target-dir]
  (if (ensure-target-directory-exists! target-dir)
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
