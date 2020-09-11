(ns genegraph.sink.batch
  (:require [genegraph.sink.event :as event]
            [genegraph.util.gcs :as gcs]
            [genegraph.env :as env]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clojure.java.shell :refer [sh]]
            [io.pedestal.log :as log])
  (:import (java.io PushbackReader File)))

(def batch-events "batch-events.edn")

(defn process-directory! 
  "Read and integrate a directory full of event records"
  [path]
  (let  [dir (File. path)
         files (filter #(re-find #".*\.edn$" (.getName %)) (file-seq dir))]
    (doseq [f files]
      (with-open [rdr (io/reader f)
                  pushback-rdr (PushbackReader. rdr)]
        (log/debug :fn :process-directory! :msg (str "processing: " f))
        (event/process-event! (edn/read pushback-rdr))))))

(defn process-batched-events! 
  "Should be run during database initialization. Download and read events stored in batch format into database."
  []
  (let [target-dir (str env/data-vol "/events/")
        batches (-> batch-events io/resource slurp edn/read-string)]
    (doseq [batch batches]
      (let [batch-dir (str target-dir (:name batch)) 
            archive-path (str batch-dir ".tar.gz")]
        (log/debug :fn :process-batched-events! :msg (:name batch))
        (fs/mkdirs batch-dir)
        (gcs/get-file-from-bucket! (:source batch) archive-path)
        (sh "tar" "-xzf" archive-path "-C" batch-dir)
        (process-directory! batch-dir)))))


