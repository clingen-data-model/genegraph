(ns clingen-search.transform.actionability
  (:require [clingen-search.database.load :as l]
            [clingen-search.database.query :as q]
            [cheshire.core :as json]
            [clojure.java.io :as io]))



(defn transform [curation]
  (let [curation-iri (get curation "iri")
        contrib-iri (l/blank-node)]
    (l/statements-to-model
     [[curation-iri :rdf/type :sepio/ActionabilityReport]
      [curation-iri :sepio/qualified-contribution contrib-iri]

      [contrib-iri :sepio/activity-date (get curation "dateISO8601")]
      [contrib-iri :bfo/realizes :sepio/ApproverRole]

      
      ])))

(defn read-local-curations [path]
  (let [files (filter #(.isFile %) (-> path io/file file-seq))]
    (map #(-> % io/reader json/parse-stream) files)))
