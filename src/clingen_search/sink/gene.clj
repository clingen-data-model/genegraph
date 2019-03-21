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
    (concat [[uri "http://www.w3.org/2004/02/skos/core#prefLabel" (:symbol gene)]
             [uri "http://www.w3.org/2004/02/skos/core#altLabel" (:name gene)]
             ^{:object :Resource} [uri "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
              "http://purl.obolibrary.org/obo/SO_0000704"]]
            (map #(vector uri "http://www.w3.org/2004/02/skos/core#hiddenLabel" %)
                 (:alias_symbol gene))
            (map #(vector uri "http://www.w3.org/2004/02/skos/core#hiddenLabel" %)
                 (:prev_name gene)))))

(defn genes-as-triple [genes-json]
  (let [genes (get-in genes-json [:response :docs])]
    (mapcat gene-as-triple genes)))

(defn load-genes [path]
  (-> path genes-from-file genes-as-triple db/load-statements))

