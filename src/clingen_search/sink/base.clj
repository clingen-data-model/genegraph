(ns clingen-search.sink.base
  (:require [clingen-search.database.tdb :as db]
            [clingen-search.sink.fetch :as fetch]
            [clingen-search.sink.gene :as gene]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]])
  (:import java.io.PushbackReader))

(def target-base "data/base/")
(def base-resources "base.edn")


(defn read-edn [resource]
  (with-open [rdr (PushbackReader. (io/reader (io/resource resource)))]
    (edn/read rdr)))

(defn read-base-resources []
  (-read-edn base-resources))

(defn retrieve-base-data [resources]
  (doseq [{uri-str :source, target-file :target, opts :fetch-opts} resources]
    (fetch/fetch-data uri-str (str target-base target-file) opts)))

(defn import-base-data [resources]
  (doseq [{source-file :target, source-type :format, opts :reader-opts} resources]
    (println "Importing " source-file)
    (with-open [is (io/input-stream (str target-base source-file))]
      (case source-type
        :rdf (db/load-rdf is opts)
        :genes (gene/load-genes is)))))

(defn- set-ns-prefixes []
  (let [prefixes (-read-edn "namespaces.edn")]
    (db/set-ns-prefixes prefixes)))
