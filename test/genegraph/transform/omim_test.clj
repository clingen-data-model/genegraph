(ns genegraph.transform.omim-test
  (:require [clojure.test :refer :all]
            [genegraph.database.util :refer [with-test-database]]
            [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.sink.stream :as s]
            [genegraph.source.graphql.gene-dosage :as d]
            [genegraph.transform.omim :as omim])
  (:import [org.apache.kafka.clients.consumer ConsumerRecord]))

;; (def base-triples
;;   [["http://purl.obolibrary.org/obo/OMIM_613090" :rdf/type :owl/Class]
;;    ["https://www.ncbi.nlm.nih.gov/gene/1188" :rdf/type (q/resource "http://purl.obolibrary.org/obo/SO_0001217")]
;;    ["http://purl.obolibrary.org/obo/MONDO_0000909" :rdfs/label "Barter disease type 4B"]
;;    ["http://purl.obolibrary.org/obo/MONDO_0000909" :owl/equivalent-class (q/resource "http://purl.obolibrary.org/obo/OMIM_613090")]
;;    ])

(def gene-dosage-record
  (ConsumerRecord. "gene_dosage_beta"
                   0
                   22125
                   1567164840234
                   org.apache.kafka.common.record.TimestampType/CREATE_TIME
                   3122576107
                   -1
                   2495
                   nil
"{
  \"@context\" : {
    \"id\" : \"@id\",
    \"type\" : \"@type\",
    \"SEPIO\" : \"http://purl.obolibrary.org/obo/SEPIO_\",
    \"PMID\" : \"https://www.ncbi.nlm.nih.gov/pubmed/\",
    \"BFO\" : \"http://purl.obolibrary.org/obo/BFO_\",
    \"CG\" : \"http://dataexchange.clinicalgenome.org/terms/\",
    \"DC\" : \"http://purl.org/dc/elements/1.1/\",
    \"OMIM\" : \"http://purl.obolibrary.org/obo/OMIM_\",
    \"MONDO\" : \"http://purl.obolibrary.org/obo/MONDO_\",
    \"FALDO\" : \"http://biohackathon.org/resource/faldo#\",
    \"NCBI_NU\" : \"https://www.ncbi.nlm.nih.gov/nuccore/\",
    \"RDFS\" : \"http://www.w3.org/2000/01/rdf-schema#\",
    \"GENO\" : \"http://purl.obolibrary.org/obo/GENO_\",
    \"IAO\" : \"http://purl.obolibrary.org/obo/IAO_\",
    \"DCT\" : \"http://purl.org/dc/terms/\",
    \"has_evidence_with_item\" : {
      \"@id\" : \"SEPIO:0000189\",
      \"@type\" : \"@id\"
    },
    \"has_predicate\" : {
      \"@id\" : \"SEPIO:0000389\",
      \"@type\" : \"@id\"
    },
    \"has_subject\" : {
      \"@id\" : \"SEPIO:0000388\",
      \"@type\" : \"@id\"
    },
    \"has_object\" : {
      \"@id\" : \"SEPIO:0000390\",
      \"@type\" : \"@id\"
    },
    \"qualified_contribution\" : {
      \"@id\" : \"SEPIO:0000159\",
      \"@type\" : \"@id\"
    },
    \"is_specified_by\" : {
      \"@id\" : \"SEPIO:0000041\",
      \"@type\" : \"@id\"
    },
    \"reference\" : {
      \"@id\" : \"FALDO:reference\",
      \"@type\" : \"@id\"
    },
    \"realizes\" : {
      \"@id\" : \"BFO:0000055\",
      \"@type\" : \"@id\"
    },
    \"source\" : {
      \"@id\" : \"DCT:source\",
      \"@type\" : \"@id\"
    },
    \"is_feature_affected_by\" : {
      \"@id\" : \"GENO:0000445\",
      \"@type\" : \"@id\"
    },
    \"label\" : \"RDFS:label\",
    \"activity_date\" : \"SEPIO:0000160\",
    \"has_count\" : \"GENO:0000917\",
    \"start_position\" : \"GENO:0000894\",
    \"end_position\" : \"GENO:0000895\",
    \"description\" : \"DC:description\"
  },
  \"id\" : \"http://dx.clinicalgenome.org/entities/ISCA-2046x1-2011-11-17T20:07:39Z\",
  \"qualified_contribution\" : {
    \"activity_date\" : \"2011-11-17T20:07:39Z\",
    \"realizes\" : \"SEPIO:0000331\"
  },
  \"has_subject\" : {
    \"id\" : \"http://dx.clinicalgenome.org/entities/ISCA-2046x1\",
    \"has_subject\" : {
      \"is_feature_affected_by\" : \"https://www.ncbi.nlm.nih.gov/gene/1188\",
      \"type\" : \"GENO:0000963\",
      \"has_count\" : 1
    },
    \"has_predicate\" : \"GENO:0000840\",
    \"type\" : \"SEPIO:0002003\",
    \"has_object\" : \"MONDO:0000001\"
  },
  \"is_specified_by\" : \"SEPIO:0002004\",
  \"has_predicate\" : \"SEPIO:0002505\",
  \"has_object\" : \"SEPIO:0002502\",
  \"type\" : \"SEPIO:0002014\"
}"))                   

(def genemap2-rows2
"#
#
#
#
chr1	16043781	16057325	1p36	1p36.13	602023	CLCNKB	Chloride channel, kidney, B	CLCNKB	1188	ENSG00000184908	unequal crossingover with CLCNKA	Bartter syndrome, type 3, 607364 (3), Autosomal recessive; Bartter syndrome, type 4b, digenic, 613090 (3), Digenic recessive	Clcnka (MGI:1329026)")

(def genemap2-rows
"#
#
#
#
  chr1	16043781	16057325	1p36	1p36.13	602023	CLCNKB	Chloride channel, kidney, B	CLCNKB	1188	ENSG00000184908	unequal crossingover with CLCNKA	Bartter syndrome, type 4b, digenic, 613090 (3), Digenic recessive	Clcnka (MGI:1329026)")





    
