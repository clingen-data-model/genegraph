(ns genegraph.migration
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [genegraph.env :as env]
            [me.raynes.fs :as fs]
            [genegraph.database.instance :as db])
  (:import [java.time ZonedDateTime ZoneOffset]
           java.time.format.DateTimeFormatter))

(defn- new-version-identifier
  "Generate a new identifier for a migration"
  []
  (.format (ZonedDateTime/now ZoneOffset/UTC) 
           (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HHmm")))

(defn build-database
  "Build the Jena database and associated indexes from scratch."
  []
  (let [version-id (new-version-identifier)
        path (str env/base-dir "/" version-id)]
    (with-redefs [env/data-vol path]
      (println env/data-vol)
      (fs/mkdirs env/data-vol)
      (mount.core/start #'db/db)
      (mount.core/stop #'db/db))))


;; create directory
;; build database
;; unmount database
;; create tarball
;; send to GCS
