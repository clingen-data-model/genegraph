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
            [cheshire.core :as json]
            [mount.core :refer [defstate]]
            [clingen-search.transform.gene]
            [clingen-search.transform.omim]
            [clingen-search.env :as env]
            [io.pedestal.log :as log])
  (:import java.io.PushbackReader
           java.time.Instant))

;; TODO ensure target directory exists
(def target-base (str env/data-vol "/base/"))
(def base-resources "base.edn")
(def state-file (str env/data-vol "/base_state.edn"))

(defstate current-state
  :start (if (.exists (io/as-file state-file))
           (atom (-> state-file slurp edn/read-string))
           (atom {})))

(defn read-edn [resource]
  (with-open [rdr (PushbackReader. (io/reader (io/resource resource)))]
    (edn/read rdr)))

(defn read-base-resources []
  (read-edn base-resources))

(defn- update-state! [resource-name k v]
  (swap! current-state assoc-in [resource-name k] v)
  (spit state-file (prn-str @current-state)))

(defn retrieve-base-data! [resources]
  (doseq [{uri-str :source, target-file :target, opts :fetch-opts, name :name} resources]
    (let [path (str target-base target-file)]
      (io/make-parents path)
      (fetch/fetch-data uri-str path opts))
    (update-state! name :retrieved (str (Instant/now)))))

(defn import-documents! [documents]
  (doseq [d documents]
    (log/info :fn :import-documents! :msg :importing :name (:name d))
    (db/load-model (transform-doc d) (:name d))
    (update-state! (:name d) :imported (str (Instant/now)))))

(defn initialize-db! []
  (let [res (read-base-resources)]
    
    (->> res
         (remove #(get-in @current-state [(:name %) :retrieved]))
         retrieve-base-data!)
    (->> res
         (remove #(get-in @current-state [(:name %) :imported]))
         import-documents!)
    (log/info :fn :initialize-db! :msg :initialization-complete)))

(defn async-initialize-db! []
  (.start (Thread. initialize-db!)))

(defn import-document [name documents]
  (import-documents! (filter #(= name (:name %)) documents)))

(defn- set-ns-prefixes []
  (let [prefixes (read-edn "namespaces.edn")]
    (db/set-ns-prefixes prefixes)))

(defn read-actionability-curations [path]
  (let [files (filter #(.isFile %) (-> path io/file file-seq))]
    (doseq [f files]
      (let [doc (-> f io/reader (json/parse-stream true))
            doc-spec {:format :actionability-v1 :name (:iri doc) :target (.getName f)}]
        (db/load-model (transform-doc doc-spec) (:name doc-spec))))))
