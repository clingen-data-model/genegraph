(ns genegraph.transform.ucsc-cytoband
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [genegraph.transform.types :refer [transform-doc src-path]]))


(def assembly-and-chr->sequence
  {:hg19 {"chr1" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000001.10"
          "chr2" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000002.11"
          "chr3" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000003.11"
          "chr4" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000004.11"
          "chr5" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000005.9"
          "chr6" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000006.11"
          "chr7" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000007.13"
          "chr8" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000008.10"
          "chr9" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000009.11"
          "chr10" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000010.10"
          "chr11" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000011.9"
          "chr12" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000012.11"
          "chr13" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000013.10"
          "chr14" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000014.8"
          "chr15" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000015.9"
          "chr16" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000016.9"
          "chr17" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000017.10"
          "chr18" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000018.9"
          "chr19" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000019.9"
          "chr20" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000020.10"
          "chr21" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000021.8"
          "chr22" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000022.10"
          "chrX" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000023.10"
          "chrY" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000024.9"}
   :hg38 {"chr1" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000001.11"
          "chr2" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000002.12"
          "chr3" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000003.12"
          "chr4" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000004.12"
          "chr5" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000005.10"
          "chr6" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000006.12"
          "chr7" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000007.14"
          "chr8" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000008.11"
          "chr9" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000009.12"
          "chr10" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000010.11"
          "chr11" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000011.10"
          "chr12" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000012.12"
          "chr13" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000013.11"
          "chr14" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000014.9"
          "chr15" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000015.10"
          "chr16" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000016.10"
          "chr17" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000017.11"
          "chr18" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000018.10"
          "chr19" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000019.10"
          "chr20" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000020.11"
          "chr21" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000021.9"
          "chr22" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000022.11"
          "chrX" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000023.11"
          "chrY" "https://www.ncbi.nlm.nih.gov/nuccore/NC_000024.10"}})

(def cytoband-root "http://dataexchange.clinicalgenome.org/terms/cytoband/")

(defn- cytoband-row->triples [assembly [chr start end cytoband]]
  (let [label (str (re-find #"\d+|X|Y" chr) cytoband)
        iri (q/resource (str cytoband-root label))
        location-iri (l/blank-node)
        interval-iri (l/blank-node)]
    [[iri :rdf/type :so/ChromosomeBand]
     [iri :rdf/type :so/SequenceFeature]
     [iri :geno/has-location location-iri]
     [iri :rdfs/label label]
     [location-iri
      :geno/has-reference-sequence
      (q/resource (get-in assembly-and-chr->sequence [assembly chr]))]
     [location-iri :rdf/type :geno/SequenceFeatureLocation]
     [location-iri :geno/has-interval interval-iri]
     [interval-iri :rdf/type :geno/SequenceInterval]
     [interval-iri :geno/start-position (Integer. start)]
     [interval-iri :geno/end-position (Integer. end)]]))

(defmethod transform-doc :ucsc-cytoband
  [doc-def]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (src-path doc-def)))
              rdr (io/reader in)]
    (l/statements-to-model
     (mapcat (partial cytoband-row->triples (::assembly doc-def)) 
             (csv/read-csv rdr :separator \tab)))))
