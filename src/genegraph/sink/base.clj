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
            [genegraph.transform.gci-express]
            [genegraph.transform.affiliations]
            [genegraph.env :as env]
            [io.pedestal.log :as log]
            [juxt.dirwatch :refer [watch-dir]])
  (:import java.io.PushbackReader
           java.time.Instant))

;; TODO ensure target directory exists
(defn target-base []
  (str env/data-vol "/base/"))

(def base-resources-edn "base.edn")

(defn read-edn [resource]
  (with-open [rdr (PushbackReader. (io/reader (io/resource resource)))]
    (edn/read rdr)))

(defn read-base-resources []
  (read-edn base-resources-edn))

(defn retrieve-base-data! [resources]
  (doseq [{uri-str :source, target-file :target, opts :fetch-opts, name :name} resources]
    (let [path (str (target-base) target-file)]
      (io/make-parents path)
      (fetch/fetch-data uri-str path opts))))

(defn import-documents! [documents]
  (doseq [d documents]
    (log/info :fn :import-documents! :msg :importing :name (:name d))
    (db/load-model (transform-doc d) (:name d))))

(defn initialize-db! []
  (let [res (read-base-resources)]
    (retrieve-base-data! res)
    (import-documents! res)
    (log/info :fn :initialize-db! :msg :initialization-complete)))

(defn import-document [name documents]
  (import-documents! (filter #(= name (:name %)) documents)))

(defn watch-base-dir
  "Watch for changes in directory containing base files and update triplestore whenever changes are noticed. Intended for use in development"
  []
  (watch-dir 
   (fn [event]
     (let [changed-documents (filter #(= (-> event :file .getName) (:target %)) 
                                     (read-base-resources))]
       (import-documents! changed-documents)))
   (io/file (target-base))))

