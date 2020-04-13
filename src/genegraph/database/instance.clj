(ns genegraph.database.instance
  "Maintains the instance of the local database"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [mount.core :as mount :refer [defstate]]
            [genegraph.env :as env])
  (:import [org.apache.jena.query.text TextDatasetFactory]))

(def assembly-file "genegraph-assembly.ttl")
(def assembly-file-expanded (str env/data-vol "/" assembly-file))

(defn get-expanded-assembly-file
  "The jena assembly file (resource/genegraph-assembly.ttl) defines where the
  tdb jena database lives and where the lucene index lives, as well as which
  fields are indexed. Unfortunately it doesn't support *nix style environment
  variable expansion. This fn creates a new file in the data dir that expands
  the $CG_SEARCH_DATA_VOL environment variable and will use that file as the
  assembly."
  []
  (when (not (.exists (io/as-file assembly-file-expanded)))
    (with-open [r (clojure.java.io/reader (io/resource assembly-file))]
      (with-open [w (clojure.java.io/writer assembly-file-expanded)]
        (doseq [line (line-seq r)]
          (.write w (string/replace line #"\$CG_SEARCH_DATA_VOL" env/data-vol))
          (.newLine w)))))
  assembly-file-expanded)

(defstate db
  :start (TextDatasetFactory/create (get-expanded-assembly-file))
  :stop (.close db))
