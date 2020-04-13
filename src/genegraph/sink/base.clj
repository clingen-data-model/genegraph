(ns genegraph.sink.base
  (:require [genegraph.database.load :as db]
            [genegraph.sink.fetch :as fetch]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [genegraph.database.query :as q]
            [genegraph.database.util :refer [tx]]
            [genegraph.transform.core :refer [transform-doc]]
            [cheshire.core :as json]
            [mount.core :refer [defstate]]
            [genegraph.transform.gene]
            [genegraph.transform.omim]
            [genegraph.transform.features]
            [genegraph.transform.loss-intolerance]
            [genegraph.transform.hi-index]
            [genegraph.env :as env]
            [io.pedestal.log :as log]
            [juxt.dirwatch :refer [watch-dir]])
  (:import java.io.PushbackReader
           java.time.Instant))

;; TODO ensure target directory exists
(def target-base (str env/data-vol "/base/"))
(def base-resources-edn "base.edn")
(def state-file (str env/data-vol "/base_state.edn"))

(defstate current-state
  :start (if (.exists (io/as-file state-file))
           (atom (-> state-file slurp edn/read-string))
           (atom {})))

(defn read-edn [resource]
  (with-open [rdr (PushbackReader. (io/reader (io/resource resource)))]
    (edn/read rdr)))

(defn read-base-resources []
  (read-edn base-resources-edn))

(def base-resources (read-base-resources))

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

(defn watch-base-dir
  "Watch for changes in directory containing base files and update triplestore whenever changes are noticed. Intended for use in development"
  []
  (watch-dir 
   (fn [event]
     (let [changed-documents (filter #(= (-> event :file .getName) (:target %)) base-resources)]
       (import-documents! changed-documents)))
   (io/file target-base)))

(defn read-actionability-curations [path]
  (let [files (filter #(.isFile %) (-> path io/file file-seq))]
    (doseq [f files]
      (let [doc (-> f io/reader (json/parse-stream true))
            doc-spec {:format :actionability-v1 :name (:iri doc) :target (.getName f)}]
        (db/load-model (transform-doc doc-spec) (:name doc-spec))))))
