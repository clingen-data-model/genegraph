(ns genegraph.source.snapshot.core
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [com.climate.claypoole :as cp]
            [genegraph.annotate :as ann]
            [genegraph.env :as env]
            [genegraph.migration :as migration]
            [genegraph.sink.document-store :as docstore]
            [genegraph.sink.event :as ev]
            [genegraph.sink.stream :as stream]
            [genegraph.source.registry.rocks-registry :as rocks-registry]
            [genegraph.source.snapshot.variation-descriptor :as variation-descriptor]
            [genegraph.transform.clinvar.ga4gh :as ga4gh]
            [genegraph.util.gcs :as gcs]
            [io.pedestal.log :as log]
            [mount.core :as mount])
  (:import (java.time Instant Duration)))

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


(def interceptor-chain
  [ann/add-metadata-interceptor
   ann/add-data-interceptor
  ;;  ann/add-iri-interceptor
   docstore/store-document-raw-key-interceptor
   #_event/stream-producer-interceptor])

(mount/defstate thread-pool
  :start (cp/threadpool 20)
  :stop (cp/shutdown thread-pool))

(defn start-states! []
  (mount/start
   #'genegraph.database.instance/db
   #'genegraph.database.property-store/property-store
   #'genegraph.transform.clinvar.cancervariants/cache-db
   #'genegraph.transform.clinvar.variation/variation-data-db
   #'genegraph.sink.event-recorder/event-database
   #'genegraph.sink.document-store/db
   #'genegraph.transform.clinvar.variation/variation-data-db
   #'genegraph.transform.clinvar.clinical-assertion/trait-data-db
   #'genegraph.transform.clinvar.clinical-assertion/trait-set-data-db
   #'genegraph.transform.clinvar.clinical-assertion/clinical-assertion-data-db
   #'genegraph.server/server
   #'rocks-registry/db
   #'rocks-registry/server
   #'genegraph.source.snapshot.core/thread-pool))


(defn run-snapshots2
  "Mostly lifted stream code from genegraph.sink.stream/store-stream

   Note, this doesn't clear existing database state in jena or the various rocksdb directories"
  [keep-running-atom]
  (log/info :msg "Populating data vol")
  (migration/populate-data-vol-if-needed)
  (log/info :msg "Loading stream data")
  #_(migration/load-stream-data env/data-vol)
  (let [topic-kw :clinvar-raw]
    (with-open [c (stream/consumer-for-topic topic-kw)]
      (let [tps (stream/topic-partitions c topic-kw)
            tp (first tps)
            end (-> (.endOffsets c [tp]) first val)]
        (when (< 1 (count tps))
          (log/error :msg "Not implemented for multi-partition topics!")
          (throw (ex-info "Not implemented for multi-partition topics!" {:tps tps})))
        (log/info :end end)
        (.assign c [tp])
        (.seekToBeginning c [tp])
        (prn :max_offsets (->> (.endOffsets c [tp])
                               (mapv #(vector [(.topic (key %)) (.partition (key %))]
                                              (val %)))
                               (into {})))
        (loop [records (stream/poll-once c)]
          (when @keep-running-atom
            (let [start (Instant/now)]
              (dorun (cp/pmap
                      thread-pool
                      (fn [record]
                        (let [start (Instant/now)]
                          (when @keep-running-atom
                            (let [event (-> record
                                            (stream/consumer-record-to-event topic-kw)
                                            (assoc ::ev/interceptors interceptor-chain)
                                            (ev/process-event!))]
                              (let [end (Instant/now)
                                    dur (Duration/between start end)]
                                ;; when dur is greater than 50 milliseconds
                                (when (< 0 (.compareTo dur (Duration/ofMillis 100)))
                                  (log/warn :msg "process-event took longer than 100 milliseconds"
                                            :duration (str dur)
                                            :event-data (:genegraph.annotate/data event))))))))
                      records))
              (log/info :progress (str (.position c tp) "/" end)
                        :batch-size (count records)
                        :batch-duration (str (Duration/between start (Instant/now)))
                        :average-duration (when (not= 0 (count records))
                                            (str (.dividedBy (Duration/between start (Instant/now))
                                                             (count records)))))
              (when (< (.position c tp) end)
                (recur (stream/poll-once c))))))))
    (if @keep-running-atom
      (do (log/info :msg "Done reading topic" :topic topic-kw)
          (ga4gh/snapshot-variation-db-rocksdb))
      (log/warn :msg "Ended snapshot due to caller signal"))))

(defonce snapshot-keep-running-atom (atom true))

(defn -main2 [& args]
  (migration/populate-data-vol-if-needed)
  (start-states!)
  (let [m (apply hash-map args)]
    (reset! snapshot-keep-running-atom true)
    (run-snapshots2 snapshot-keep-running-atom)))

(comment
  #_(migration/populate-data-vol-if-needed)
  (def snapshot-thread (doto (Thread. -main2)
                         .start))

  (reset! snapshot-keep-running-atom false))
