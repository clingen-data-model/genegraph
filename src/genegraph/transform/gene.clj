(ns genegraph.transform.gene
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [genegraph.database.load :as db]
            [genegraph.transform.core :refer [transform-doc src-path]]
            [genegraph.database.query :refer [resource]]))

;; symbol -> skos:prefLabel ? rdf:label
;; name -> skos:altLabel 
;; everything else that needs to be searchable -> skos:hiddenLabel
;; uri -> munge of entrez id and https://www.ncbi.nlm.nih.gov/gene/

(def hgnc  "https://www.genenames.org")
(def ensembl  "https://www.ensembl.org")

(defn genes-from-file [path]
  (with-open [rdr (io/reader path)]
    (json/parse-stream rdr true)))

(defn gene-as-triple [gene]
  (let [uri (str "https://www.ncbi.nlm.nih.gov/gene/" (:entrez_id gene))
        hgnc-id (:hgnc_id gene)
        ensembl-iri (str "http://rdf.ebi.ac.uk/resource/ensembl/"
                                    (:ensembl_gene_id gene))]
    (concat [[uri :skos/preferred-label (:symbol gene)]
             [uri :skos/alternative-label (:name gene)]
             ^{:object :Resource} [uri :owl/same-as hgnc-id]
             [hgnc-id :dc/source (resource hgnc)]
             ^{:object :Resource} [uri :owl/same-as ensembl-iri]
             [ensembl-iri :dc/source (resource ensembl)]
             [uri :rdf/type :so/Gene]]
            (map #(vector uri :skos/hidden-label %)
                 (:alias_symbol gene))
            (map #(vector uri :skos/hidden-label %)
                 (:prev_name gene)))))

(defn genes-as-triple [genes-json]
  (let [genes (get-in genes-json [:response :docs])]
    (mapcat gene-as-triple genes)))


(defmethod transform-doc :genes
  ([doc-def] (transform-doc doc-def (slurp (src-path doc-def))))
  ([doc-def src] (-> src (json/parse-string true) genes-as-triple db/statements-to-model)))

(defn load-genes [path]
  (-> path genes-from-file genes-as-triple (db/load-statements "https://www.genenames.org/")))

