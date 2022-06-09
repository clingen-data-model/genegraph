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

(defn- join-dedup-delimiters
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
  "Accepts a map of params
  E.g. {:until \"2020-01-01\"}"
  [params]
  (log/info :fn :run-snapshots :params params)
  (let [version-id (migration/get-version-id)
        database-path (s/join "/" [env/base-dir version-id])]
    ;(migration/build-database database-path)
    (with-redefs [env/data-vol database-path]
      (log/info :fn :run-snapshots :msg (str "Redefined data-vol as " env/data-vol))
      (migration/populate-data-vol-if-needed)
      (log/info :fn :run-snapshots :msg "Loading stream data...")
      (migration/load-stream-data env/data-vol)
      (let [descriptors-graphql (genegraph.source.snapshot.variation-descriptor/variation-descriptors-as-of-date {:until (:until params)})]
        (let [descriptors-json (map json/generate-string descriptors-graphql)
              output-string (s/join "\n" descriptors-json)]
          (let [blob-path-in-bucket (s/join "/" ["snapshots" version-id "CategoricalVariationDescriptor" "00000000"])
                write-channel-fn (gcs/get-bucket-write-channel blob-path-in-bucket)]
            (log/info :fn :run-snapshots
                      :msg (format "Writing json (%d bytes) to %s"
                                   (count output-string)
                                   blob-path-in-bucket))
            (with-open [write-channel (write-channel-fn)]
              (gcs/channel-write-string! write-channel output-string))
            (log/info :fn :run-snapshots :msg "Done writing snapshot output")
            descriptors-json))))))

(defn keywordize
  "Return string S or keyword :k when string S starts with :."
  [s]
  (if (.startsWith s ":") (keyword (subs s 1)) s))

(defn -main [& args]
  (let [m (apply hash-map args)
        params (zipmap (map keywordize (keys m)) (vals m))]
    (run-snapshots params)))