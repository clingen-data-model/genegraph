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
            [genegraph.transform.rxnorm]
            [genegraph.env :as env]
            [io.pedestal.log :as log]
            [juxt.dirwatch :refer [watch-dir]]
            [genegraph.util.gcs :as gcs])
  (:import java.io.PushbackReader
           java.time.Instant))

(def source-path (if env/migration-data-vol (str env/migration-data-vol "/base/") nil))
(def base-resources-edn "base.edn")

;; TODO ensure target directory exists
(defn target-base []
  (str env/data-vol "/base/"))

(defn read-edn [resource]
  (with-open [rdr (PushbackReader. (io/reader (io/resource resource)))]
    (edn/read rdr)))

(defn read-base-resources []
  (read-edn base-resources-edn))

(defn fetch-resource! [resource]
  (let [{uri-str :source, target-file :target, opts :fetch-opts, name :name} resource
        source-uri (if source-path (str "file://" source-path target-file) uri-str)
        target-path (str (target-base) target-file)]
    (io/make-parents target-path)
    (try
      (fetch/fetch-data source-uri target-path opts)
      (catch Exception e
        (log/error :fn :retrieve-base-data :resource name :source-uri source-uri :target-path target-path)
        (throw e)))))

(defn retrieve-base-data! [resources]
  (doall (pmap fetch-resource! resources)))

(defn import-documents! [documents]
  (doall (pmap (fn [d]
                 (log/debug :fn :import-documents! :msg :importing :name (:name d))
                 (db/load-model (transform-doc d) (:name d)))
               documents)))

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

;; May want to refer to an environment variable in future
;; if multiple potential base origins are desired
(defn retrieve-base-data-from-gcp! []
  (gcs/get-files-with-prefix! "current-base/" (target-base)))

(defn push-base-data-to-gcp! []
  (gcs/push-directory-to-bucket! (target-base) "current-base/"))

(defn initialize-db! []
  (let [res (read-base-resources)]
    (retrieve-base-data-from-gcp!)
    (import-documents! res)
    (log/debug :fn :initialize-db! :msg :initialization-complete)))
