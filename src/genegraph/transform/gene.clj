(ns genegraph.transform.gene
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [io.pedestal.log :as log]
            [genegraph.database.load :as db]
            [genegraph.transform.types :refer [transform-doc add-model src-path]]
            [genegraph.database.query :refer [resource]]
            [genegraph.database.query :as q]))

;; symbol -> skos:prefLabel ? rdf:label
;; name -> skos:altLabel 
;; everything else that needs to be searchable -> skos:hiddenLabel
;; uri -> munge of entrez id and https://www.ncbi.nlm.nih.gov/gene/

(def hgnc  "https://www.genenames.org")
(def ensembl  "https://www.ensembl.org")

(defn genes-from-file [path]
  (with-open [rdr (io/reader path)]
    (json/parse-stream rdr true)))

(def locus-types {"immunoglobulin gene" "http://purl.obolibrary.org/obo/SO_0002122"
                  "T cell receptor gene" "http://purl.obolibrary.org/obo/SO_0002099"
                  "RNA, micro" "http://purl.obolibrary.org/obo/SO_0000276"
                  "gene with protein product" "http://purl.obolibrary.org/obo/SO_0001217"
                  "RNA, transfer" "http://purl.obolibrary.org/obo/SO_0000253"
                  "pseudogene" "http://purl.obolibrary.org/obo/SO_0000336"
                  "RNA, long non-coding" "http://purl.obolibrary.org/obo/SO_0001877"
                  "virus integration site" "http://purl.obolibrary.org/obo/SO_0000946?"
                  "RNA, vault" "http://purl.obolibrary.org/obo/SO_0000404"
                  "endogenous retrovirus" "http://purl.obolibrary.org/obo/SO_0000100"
                  "RNA, small nucleolar" "http://purl.obolibrary.org/obo/SO_0000275"
                  "T cell receptor pseudogene" "http://purl.obolibrary.org/obo/SO_0002099"
                  "immunoglobulin pseudogene" "http://purl.obolibrary.org/obo/SO_0002098"
                  "RNA, small nuclear" "http://purl.obolibrary.org/obo/SO_0000274"
                  "readthrough" "http://purl.obolibrary.org/obo/SO_0000883"
                  "RNA, ribosomal" "http://purl.obolibrary.org/obo/SO_0000252"
                  "RNA, misc" "http://purl.obolibrary.org/obo/SO_0000356"})

;; TODO add cyto location, locus type
(defn gene-as-triple [gene]
  (let [uri (str "https://www.ncbi.nlm.nih.gov/gene/" (:entrez_id gene))
        hgnc-id (:hgnc_id gene)
        ensembl-iri (str "http://rdf.ebi.ac.uk/resource/ensembl/"
                                    (:ensembl_gene_id gene))]
    (remove nil?
            (concat [[uri :skos/preferred-label (:symbol gene)]
                     [uri :skos/alternative-label (:name gene)]
                     (when-let [loc (:location gene)] [uri :so/chromosome-band loc])
                     (when-let [locus-type (locus-types (:locus_type gene))]
                       [uri :rdf/type (resource locus-type)])
                     ^{:object :Resource} [uri :owl/same-as hgnc-id]
                     [hgnc-id :dc/source (resource hgnc)]
                     ^{:object :Resource} [uri :owl/same-as ensembl-iri]
                     [ensembl-iri :dc/source (resource ensembl)]]
                    (map #(vector uri :skos/hidden-label %)
                         (:alias_symbol gene))
                    (map #(vector uri :skos/hidden-label %)
                         (:prev_name gene))
                    (map #(vector uri :skos/hidden-label %)
                         (:prev_symbol gene))))))

(defn genes-as-triple [genes-json]
  (let [genes (get-in genes-json [:response :docs])]
    (conj (mapcat gene-as-triple genes)
          ["https://www.genenames.org/" :rdf/type :void/Dataset])))

(defmethod transform-doc :genes
  ([doc-def] (transform-doc doc-def (slurp (src-path doc-def))))
  ([doc-def src] (-> src (json/parse-string true) genes-as-triple db/statements-to-model)))

(defn load-genes [path]
  (-> path genes-from-file genes-as-triple (db/load-statements "https://www.genenames.org/")))

(defmethod add-model :hgnc-genes [event]
  (log/debug :fn :add-model :format :hgnc-genes :event event :msg :received-event)
  (let [model (-> event 
                  :genegraph.sink.event/value
                  (json/parse-string true)
                  genes-as-triple
                  db/statements-to-model)]
    (assoc event ::q/model model )))
