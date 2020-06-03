(ns genegraph.migration
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [genegraph.env :as env]
            [me.raynes.fs :as fs]
            [genegraph.sink.base :as base]
            [genegraph.database.instance :as db]
            [genegraph.sink.stream :as stream]
            [mount.core :refer [start stop]])
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
      (start #'db/db)
      (base/initialize-db!)
      (start #'stream/consumer-thread)
      ;; TODO There seems to be a race condition here
      ;; the threads shut down almost as soon as they start.
      ;; Sleep for a couple minutes to let them warm up
      (Thread/sleep (* 1000 60 2))
      (while (not (stream/up-to-date?))
        (Thread/sleep (* 1000 10)))
      (stop #'stream/consumer-thread)
      (while (not (stream/consumers-closed?))
        (Thread/sleep 1000))
      (stop #'db/db))))


;; create directory
;; build database
;; unmount database
;; create tarball
;; send to GCS
