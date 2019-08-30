(ns clingen-search.migration
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clingen-search.env :as env]
            [me.raynes.fs :as fs])
  (:import java.time.Instant))

(def version-file (str env/data-vol "/version.edn"))

(defn desired-version []  
  (-> "version.edn"
      io/resource
      slurp
      edn/read-string
      :migration))

(defn current-version 
  "If version.edn does not exist in data, return -1 for migration index (always build data)"
  []
  (if (.exists (io/as-file version-file))
    (-> version-file
        slurp
        edn/read-string
        :migration)
    -1))

(defn needs-migration? []
  (< (current-version) (desired-version)))

(defn migrate! 
  "If a migration is needed, clear the current application state and rely on the app init
  code to rebuild the database appropriately."
  []
  (when (needs-migration?)
    (fs/delete-dir (str env/data-vol "/tdb"))
    (fs/delete-dir (str env/data-vol "/base"))
    (fs/delete (str env/data-vol "base_state.edn"))
    (fs/delete (str env/data-vol "partition_offsets.edn"))
    (spit version-file (pr-str {:migration (desired-version)}))))
