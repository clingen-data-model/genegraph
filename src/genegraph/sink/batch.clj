(ns genegraph.sink.batch
  (:require [genegraph.sink.event :as event]
            [genegraph.util.gcs :as gcs]
            [genegraph.env :as env]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clojure.java.shell :refer [sh]]
            [io.pedestal.log :as log]
            [cheshire.core :as json])
  (:import (java.io PushbackReader File)))

(def batch-events "batch-events.edn")

(defn target-path [filename]
  (str env/data-vol "/events/" filename))

(defn process-directory! 
  "Read and integrate a directory full of event records"
  [path]
  (let [dir (File. path)
        files (filter #(re-find #".*\.edn$" (.getName %)) (file-seq dir))
        events (map #(with-open [rdr (io/reader %)
                                 pushback-rdr (PushbackReader. rdr)]
                       (edn/read pushback-rdr))
                    files)]
    (event/process-event-seq! events)))

(defn process-compressed-event-file!
  "process a tarball containing multiple gzipped events"
  [descriptor]
  (let [batch-dir (target-path (:name descriptor))
        archive-path (str batch-dir ".tar.gz")]
    (log/debug :fn :process-batched-events! :msg (:name descriptor))
    (fs/mkdirs batch-dir)
    (gcs/get-file-from-bucket! (:source descriptor) archive-path)
    (sh "tar" "-xzf" archive-path "-C" batch-dir)
    (process-directory! batch-dir)))


(defn process-json-event-sequence!
  "read and process a json file with a sequence of event records"
  [descriptor]
  (let [path (target-path (:name descriptor))]
    (gcs/get-file-from-bucket! (:source descriptor) path)
    (with-open [r (io/reader path)]
      (let [events (map (fn [json-evt]
                          {:genegraph.annotate/format (:format descriptor)
                           ::event/value json-evt})
                        (json/parse-stream r true))]
        (event/process-event-seq! events)))))

(defn process-batched-events! 
  "Should be run during database initialization. Download and read events stored in batch format into database."
  []
  (doseq [descriptor (-> batch-events io/resource slurp edn/read-string)]
    (case (:type descriptor)
      :compressed-event-files (process-compressed-event-file! descriptor)
      :json-event-sequence (process-json-event-sequence! descriptor))))


