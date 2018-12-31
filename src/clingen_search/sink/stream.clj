(ns clingen-search.sink.stream
  (:require [clingen-search.database.tdb :as db]
            [clojure.java.io :as io]))

(defn load-local-data 
  "Treat all files stored in dir as loadable data in json-ld form, load them
  into base datastore"
  [dir]
  (let [files (filter #(.isFile %) (-> dir io/file file-seq))]
    (doseq [file files]
      (println "importing " (.getName file))
      (with-open [is (io/input-stream file)]
        (db/load-rdf is :format :json-ld)))))
