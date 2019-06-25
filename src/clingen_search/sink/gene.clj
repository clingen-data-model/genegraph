(ns clingen-search.sink.gene
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clingen-search.database.load :as db]))

;; symbol -> skos:prefLabel ? rdf:label
;; name -> skos:altLabel 
;; everything else that needs to be searchable -> skos:hiddenLabel
;; uri -> munge of entrez id and https://www.ncbi.nlm.nih.gov/gene/

(defn genes-from-file [path]
  (with-open [rdr (io/reader path)]
    (json/parse-stream rdr true)))

(defn gene-as-triple [gene]
  (let [uri (str "https://www.ncbi.nlm.nih.gov/gene/" (:entrez_id gene))]
    (concat [[uri :skos/preferred-label (:symbol gene)]
             [uri :skos/alternative-label (:name gene)]
             [uri :rdf/type :so/Gene]]
            (map #(vector uri :skos/hidden-label %)
                 (:alias_symbol gene))
            (map #(vector uri :skos/hidden-label %)
                 (:prev_name gene)))))

(defn genes-as-triple [genes-json]
  (let [genes (get-in genes-json [:response :docs])]
    (mapcat gene-as-triple genes)))

(defn load-genes [path]
  (-> path genes-from-file genes-as-triple (db/load-statements "https://www.genenames.org/")))

