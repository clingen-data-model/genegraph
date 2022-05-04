(ns genegraph.source.snapshot.core
  (:require [genegraph.source.snapshot.variation-descriptor :as variation-descriptor]
            [genegraph.env :as env]
            [io.pedestal.log :as log]
            [genegraph.migration :as migration]
            [clojure.string :as s]
            [cheshire.core :as json]
            [genegraph.util.gcs :as gcs]
            [genegraph.transform.clinvar.util :as util])
  (:import (java.nio.channels WritableByteChannel)
           (java.nio.charset StandardCharsets)
           (java.nio ByteBuffer)
           ))

(def snapshot-bucket env/genegraph-bucket)
(def snapshot-path "snapshots")

(defn write-json-seq-to-file [values file-handle])

(defn join-dedup-delimiters
  "Joins the values seq with delim between each, not adding a delim in a location if one will already be present."
  [delim values]
  (let [processed
        (loop [todo values
               terms []]
          (if (empty? todo)
            (s/join delim terms)
            (let [trimmed (-> (first todo)
                              (#(if (.startsWith % delim) (.substring % (.length delim)) %))
                              (#(if (.endsWith % delim) (.substring % 0 (- (.length %) (.length delim))) %)))]
              (recur (rest todo)
                     (conj terms trimmed)))))]
    ; Add back leading and trailing delims if they were present in first/last input values
    (cond-> processed
            (.startsWith (first values) delim) (->> (str delim))
            (.endsWith (last values) delim) (str delim))))

(defn run-snapshots
  "Accepts an args seq of key-value pairs.
  E.g. [:until \"2020-01-01\"]"
  [args]
  (log/info :fn :run-snapshots :msg "Building database...")
  (let [version-id (migration/get-version-id)
        database-path (join-dedup-delimiters "/" [env/base-dir version-id])]
    ;(migration/build-database database-path)
    (with-redefs [env/data-vol database-path]
      (log/info :fn :run-snapshots :msg (str "Redefined data-vol as " env/data-vol))
      (migration/populate-data-vol-if-needed)
      (migration/load-stream-data env/data-vol)

      (let [params (apply hash-map args)
            descriptors-graphql (genegraph.source.snapshot.variation-descriptor/variation-descriptors-as-of-date {:until (:until params)})
            descriptors-json (map json/generate-string descriptors-graphql)]
        (let [blob-path-in-bucket (join-dedup-delimiters "/" ["snapshots" version-id "CategoricalVariationDescriptor" "00000000"])
              write-channel (gcs/get-bucket-write-channel blob-path-in-bucket)]
          ;(gcs/channel-write-string write-channel descriptors-json)
          (log/info :fn :run-snapshots :msg "Writing json to ")
          )))))
