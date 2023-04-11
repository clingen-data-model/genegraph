(ns genegraph.source.snapshot.core
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.string :as str]
            [com.climate.claypoole :as cp]
            [genegraph.annotate :as ann]
            [genegraph.env :as env]
            [genegraph.migration :as migration]
            [genegraph.sink.document-store :as docstore]
            [genegraph.sink.event :as ev]
            [genegraph.sink.stream :as stream]
            [genegraph.source.registry.rocks-registry :as rocks-registry]
            [genegraph.source.snapshot.variation-descriptor :as variation-descriptor]
            genegraph.transform.clinvar.clinical-assertion
            [genegraph.transform.clinvar.ga4gh :as ga4gh]
            genegraph.transform.clinvar.variation
            [genegraph.util.fs :refer [gzip-file-writer]]
            [genegraph.util.gcs :as gcs]
            [io.pedestal.log :as log]
            [me.raynes.fs :as fs]
            [mount.core :as mount])
  (:import (java.time Duration Instant)))


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

(defn keywordize
  "Return string S or keyword :k when string S starts with :."
  [s]
  (if (.startsWith s ":") (keyword (subs s 1)) s))


(defn wrap-with-exception-catcher [interceptor]
  (-> interceptor
      (update-in [:enter] (fn [f] (when f ())))))

(def interceptor-chain
  [ann/add-metadata-interceptor
   ann/add-data-interceptor
   #_ann/add-iri-interceptor
   docstore/store-document-raw-key-interceptor
   #_event/stream-producer-interceptor])

(mount/defstate thread-pool
  :start (cp/threadpool 20)
  :stop (cp/shutdown thread-pool))

(defn start-states! []
  (mount/start
   #'genegraph.database.instance/db
   #'genegraph.sink.event-recorder/event-database
   #'genegraph.sink.document-store/db
   #'genegraph.database.property-store/property-store
   #'genegraph.transform.clinvar.cancervariants/cache-db
   #'genegraph.transform.clinvar.variation/variation-data-db
   #'genegraph.transform.clinvar.clinical-assertion/trait-data-db
   #'genegraph.transform.clinvar.clinical-assertion/trait-set-data-db
   #'genegraph.transform.clinvar.clinical-assertion/clinical-assertion-data-db
   #'genegraph.transform.clinvar.core/release-sentinel-snapshot-db
   #'genegraph.server/server
   #'rocks-registry/db
   #'rocks-registry/server
   #'genegraph.source.snapshot.core/thread-pool))

(defn write-snapshots
  "DATASETS is a map of output filename to the rocksdb object its content will be based on
   {\"variation.ndjson\" myvariation-snapshot-db}

   OPTIONS can be:
   - :output-vol : the root location on the filesystem to export snapshots to.
       For example a mountpoint, or genegraph data-vol
       Defaults to env/data-vol.
   - :output-prefix : within :output-vol, the directory to export *this* snapshot job to.
       For example a directory with the current timestamp, data version, or code version.
       Defaults to snapshots/<current-iso-timestamp>

   Returns a map with :directory and :files.
   Directory is the top level directory for the outputs of this job.
   Files are the file paths under that directory, which may have multiple sub-directories.
   Example:

   {:output-vol \"/data/2021-11-19T2032\",
   :output-prefix \"snapshots/2023-04-10T23:44:14.850519Z\",
   :files
   [\"variation-descriptors.ndjson.gz\"
    \"statements.ndjson.gz\"]}
  "
  ([datasets] (write-snapshots datasets {}))
  ([datasets {:keys [gzip output-vol output-prefix]
              :or {gzip false
                   output-vol env/data-vol
                   output-prefix (str "snapshots/" (Instant/now))}
              :as options}]
   (let [output-full-directory (str output-vol "/" output-prefix)
         start (Instant/now)]
     (fs/mkdirs output-full-directory)
     (loop [datasets datasets
            output {:output-vol output-vol
                    :output-prefix output-prefix
                    :files []}]
       (if (empty? datasets)
         output
         (let [[filename db] (first datasets)
               db (if (var? db) (var-get db) db)
               filename (cond-> filename gzip (str ".gz"))
               vol-path (str output-prefix "/" filename)
               full-path (str output-vol "/" vol-path)]
           (log/info :fn :write-snapshots :timestamp (str start) :path vol-path)
           (with-open [writer (if gzip
                                (gzip-file-writer full-path)
                                (io/writer full-path))]
             (doseq [[i [entry-k entry-v]] (map-indexed vector (ga4gh/latest-records db))]
               (when (= 0 (rem i 1000))
                 (log/info :snapshot filename :progress i))
               (.write writer (json/generate-string entry-v))
               (.write writer "\n")))
           (recur (rest datasets)
                  (update output :files conj filename))))))))

(def snapshot-datasets
  {"variation-descriptors.ndjson"
   #'genegraph.transform.clinvar.variation/variation-data-db
   "statements.ndjson"
   #'genegraph.transform.clinvar.clinical-assertion/clinical-assertion-data-db})

(defn write-snapshot-outputs-to-bucket
  "Takes a map in the form returned by write-snapshots, copies them to the configured bucket

   Examples:

   {basename relative-file-path}

   {\"variation.ndjson\" \"snapshots/2023-04-05T12:01:05.0Z/variation.ndjson\"}"
  [{:keys [output-vol output-prefix files]
    :as snapshot-output-map}]
  (doseq [filename files]
    (let [local-path (str output-vol "/" output-prefix "/" filename)
          path-in-bucket (str output-prefix "/" filename)]
      (log/info :msg "Writing file to bucket"
                :local-file local-path
                :bucket-file path-in-bucket
                :bucket env/genegraph-bucket)
      (gcs/put-file-in-bucket! local-path path-in-bucket))))

(defn process-event-catch-exceptions [event]
  (try (ev/process-event! event)
       (catch Exception e
         (log/error :msg "Exception in process-event"
                    :event event :e e)
         (update event :exceptions (fn [es] (concat [] es e))))))

(defn end-offsets
  "Like KafkaConsumer.endOffsets, but returns in {[topic partition-num] offset, ... } format"
  [consumer topic-partitions]
  (->> (.endOffsets consumer topic-partitions)
       (mapv #(vector [(.topic (key %)) (.partition (key %))]
                      (val %)))
       (into {})))

(defn snapshot-populate
  "Lifted some stream-related code from genegraph.sink.stream/store-stream

   This doesn't clear existing database state in jena or the various rocksdb directories"
  [topic-kw keep-running-atom]
  (let [topic-kw :clinvar-raw
        start (Instant/now)]
    (log/info :fn :run-snapshots2 :start-time (str start))
    (with-open [c (stream/consumer-for-topic topic-kw)]
      (let [tps (stream/topic-partitions c topic-kw)
            tp (first tps)
            end (-> (.endOffsets c [tp]) first val)
            end (min 10 end)]
        (when (< 1 (count tps))
          (throw (ex-info "Not implemented for multi-partition topics!" {:tps tps})))
        (.assign c [tp])
        (.seekToBeginning c [tp])
        (while (and (< (.position c tp) end)
                    @keep-running-atom)
          (let [records (stream/poll-once c)
                start (Instant/now)]
            (dorun (cp/pmap
                    thread-pool
                    (fn [record]
                      (let [start (Instant/now)]
                        (when @keep-running-atom
                          (let [event (-> record
                                          (stream/consumer-record-to-event topic-kw)
                                          (assoc ::ev/interceptors interceptor-chain)
                                          (process-event-catch-exceptions))]
                            (let [end (Instant/now)
                                  dur (Duration/between start end)]
                              (when (< 0 (.compareTo dur (Duration/ofMillis 200)))
                                (log/warn :msg "process-event took longer than 200 milliseconds"
                                          :duration (str dur)
                                          :event-data (:genegraph.annotate/data event))))))))
                    records))
            (log/info :progress (str (.position c tp) "/" end)
                      :batch-size (count records)
                      :batch-duration (str (Duration/between start (Instant/now)))
                      :average-duration (when (not= 0 (count records))
                                          (str (.dividedBy (Duration/between start (Instant/now))
                                                           (count records))))))))))
  (when (not @keep-running-atom)
    (log/warn :msg "Ended snapshot due to caller signal")))

(defn snapshot-write [topic-kw keep-running-atom]
  (if (not @keep-running-atom)
    (log/warn :msg "Ended snapshot due to caller signal")
    (let [{:keys [output-vol output-prefix output-files]
           :as output-file-map}
          (write-snapshots snapshot-datasets {:gzip true})]
      (log/info :msg "Finished writing snapshot files" :files output-files)
      (when true ;; env/snapshot-upload
        (log/info :msg "Uploading snapshot outputs to bucket"
                  :bucket env/genegraph-bucket :files output-files)
        (write-snapshot-outputs-to-bucket output-file-map)
        (let [db-vars (map second snapshot-datasets)]
          (doseq [db-var (map second snapshot-datasets)]
            (let [abs-path (-> db-var var-get (.getName))
                  basename (-> abs-path (str/split #"/") last)
                  archive-abs-path (-> abs-path (str ".gz"))
                  bucket-archive-path (str output-prefix "/" basename ".gz")]
              (log/info :msg "Uploading rocksdb instance"
                        :db-var db-var :basename basename
                        :bucket-path bucket-archive-path)
              (log/info :msg "Temporarily stopping rocksdb state"
                        :db-var db-var)
              (mount/stop db-var)
              (let [ret (migration/compress-database abs-path archive-abs-path)]
                (if (= false ret)
                  (log/error :msg "failed to compress rocksdb database"
                             :db-var db-var :abs-path abs-path
                             :archive-abs-path archive-abs-path)
                  (gcs/put-file-in-bucket! archive-abs-path bucket-archive-path)))

              (log/info :msg "Restarting rocksdb state"
                        :db-var db-var)
              (mount/start db-var))))))))

(defonce snapshot-keep-running-atom (atom true))

(defn -main2 [& args]
  (migration/populate-data-vol-if-needed)
  (start-states!)
  (let [m (apply hash-map args)]
    (reset! snapshot-keep-running-atom true)
    (snapshot-populate :clinvar-raw snapshot-keep-running-atom)
    (snapshot-write :clinvar-raw snapshot-keep-running-atom)))

(comment
  (def snapshot-thread (doto (Thread. -main2)
                         .start))

  (reset! snapshot-keep-running-atom false))
