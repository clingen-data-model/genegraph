(ns clingen-search.sink.base
  (:require [clingen-search.database.load :as db]
            [clingen-search.sink.fetch :as fetch]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [clingen-search.sink.validation :as v]
            [clingen-search.database.query :as q]
            [clingen-search.database.util :refer [tx]]
            [clingen-search.transform.core :refer [transform-doc]]
            [cheshire.core :as json])
  (:import java.io.PushbackReader))

;; TODO ensure target directory exists
(def target-base (str (System/getenv "CG_SEARCH_DATA_VOL") "/base/"))
(def base-resources "base.edn")

(defn read-edn [resource]
  (with-open [rdr (PushbackReader. (io/reader (io/resource resource)))]
    (edn/read rdr)))

(defn read-base-resources []
  (read-edn base-resources))

(defn retrieve-base-data [resources]
  (doseq [{uri-str :source, target-file :target, opts :fetch-opts} resources]
    (fetch/fetch-data uri-str (str target-base target-file) opts)))

(defn import-documents [documents]
  (doseq [d documents]
    (println "Importing " (:name d))
    (db/load-model (transform-doc d) (:name d))))

(defn import-document [name documents]
  (import-documents (filter #(= name (:name %)) documents)))

(defn- set-ns-prefixes []
  (let [prefixes (read-edn "namespaces.edn")]
    (db/set-ns-prefixes prefixes)))

(defn read-actionability-curations [path]
  (let [files (filter #(.isFile %) (-> path io/file file-seq))]
    (doseq [f files]
      (let [doc (-> f io/reader (json/parse-stream true))
            doc-spec {:format :actionability-v1 :name (:iri doc) :target (.getName f)}]
        (println (.getName f))
        (db/load-model (transform-doc doc-spec) (:name doc-spec))))))
