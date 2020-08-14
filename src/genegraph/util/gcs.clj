(ns genegraph.util.gcs
  (:require [genegraph.env :as env]
            [me.raynes.fs :as fs])
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

(defn put-file-in-bucket!
  ([source-file blob-name]
   (put-file-in-bucket! source-file blob-name {:content-type "application/gzip"}))
  ([source-file blob-name options]
   (let [gc-storage (.getService (StorageOptions/getDefaultInstance))
         blob-id (BlobId/of env/genegraph-bucket blob-name)
         blob-info (-> blob-id BlobInfo/newBuilder (.setContentType (:content-type options)) .build)
         from (.getChannel (FileInputStream. source-file))]
     (with-open [to (.writer gc-storage blob-info (make-array Storage$BlobWriteOption 0))]
       (ByteStreams/copy from to)))))

(defn get-file-from-bucket!
  [source-blob target-file]
  (let [target-path (Paths/get target-file
                               (make-array java.lang.String 0))
        blob (.get (.getService (StorageOptions/getDefaultInstance))
                   (BlobId/of env/genegraph-bucket source-blob))]
    (println source-blob " " target-file)
    (.downloadTo blob target-path)))
