(ns genegraph.sink.stream-test
  (:require [clojure.test :refer :all]
            [genegraph.sink.stream :refer :all]
            [genegraph.database.util :refer [with-test-database]]
            [genegraph.database.query :as q]
            [genegraph.sink.base :as b])
  (:import [org.apache.kafka.clients.consumer ConsumerRecord]))

(def good-dosage-records
  [(ConsumerRecord. "gene_dosage_sepio_in"
                   0
                   5851
                   1576001647147
                   org.apache.kafka.common.record.TimestampType/CREATE_TIME
                   3047257890
                   -1
                   4429
                   nil
                   "{\"@context\":{\"id\":\"@id\",\"type\":\"@type\",\"SEPIO\":\"http://purl.obolibrary.org/obo/SEPIO_\",\"PMID\":\"https://www.ncbi.nlm.nih.gov/pubmed/\",\"BFO\":\"http://purl.obolibrary.org/obo/BFO_\",\"CG\":\"http://dataexchange.clinicalgenome.org/terms/\",\"DC\":\"http://purl.org/dc/elements/1.1/\",\"OMIM\":\"http://purl.obolibrary.org/obo/OMIM_\",\"MONDO\":\"http://purl.obolibrary.org/obo/MONDO_\",\"FALDO\":\"http://biohackathon.org/resource/faldo#\",\"NCBI_NU\":\"https://www.ncbi.nlm.nih.gov/nuccore/\",\"RDFS\":\"http://www.w3.org/2000/01/rdf-schema#\",\"GENO\":\"http://purl.obolibrary.org/obo/GENO_\",\"IAO\":\"http://purl.obolibrary.org/obo/IAO_\",\"DCT\":\"http://purl.org/dc/terms/\",\"SO\":\"http://purl.obolibrary.org/obo/SO_\",\"has_evidence_with_item\":{\"@id\":\"SEPIO:0000189\",\"@type\":\"@id\"},\"has_predicate\":{\"@id\":\"SEPIO:0000389\",\"@type\":\"@id\"},\"has_subject\":{\"@id\":\"SEPIO:0000388\",\"@type\":\"@id\"},\"has_object\":{\"@id\":\"SEPIO:0000390\",\"@type\":\"@id\"},\"qualified_contribution\":{\"@id\":\"SEPIO:0000159\",\"@type\":\"@id\"},\"is_specified_by\":{\"@id\":\"SEPIO:0000041\",\"@type\":\"@id\"},\"reference\":{\"@id\":\"FALDO:reference\",\"@type\":\"@id\"},\"realizes\":{\"@id\":\"BFO:0000055\",\"@type\":\"@id\"},\"source\":{\"@id\":\"DCT:source\",\"@type\":\"@id\"},\"is_feature_affected_by\":{\"@id\":\"GENO:0000445\",\"@type\":\"@id\"},\"has_part\":{\"@id\":\"BFO:0000051\",\"@type\":\"@id\"},\"is_version_of\":{\"@id\":\"DCT:isVersionOf\",\"@type\":\"@id\"},\"interval\":{\"@id\":\"GENO:0000966\",\"@type\":\"@id\"},\"has_location\":{\"@id\":\"GENO:0000903\",\"@type\":\"@id\"},\"is_about\":{\"@id\":\"IAO:0000136\",\"@type\":\"@id\"},\"label\":\"RDFS:label\",\"activity_date\":\"SEPIO:0000160\",\"has_count\":\"GENO:0000917\",\"start\":\"GENO:0000894\",\"end\":\"GENO:0000895\",\"description\":\"DC:description\",\"sequence_id\":{\"@id\":\"GENO:0000967\",\"@type\":\"@id\"},\"SimpleInterval\":\"GENO:0000965\",\"SequenceLocation\":\"GENO:0000815\",\"SequenceFeature\":\"SO:0000110\"},\"type\":\"SEPIO:0002015\",\"id\":\"http://dx.clinicalgenome.org/entities/ISCA-30006-2019-08-13T18:49:43Z\",\"is_version_of\":\"http://dx.clinicalgenome.org/entities/ISCA-30006\",\"qualified_contribution\":{\"activity_date\":\"2019-08-13T18:49:43Z\",\"realizes\":\"SEPIO:0000331\"},\"is_about\":\"https://www.ncbi.nlm.nih.gov/gene/5925\",\"has_part\":[{\"description\":\"Hereditary retinoblastoma is caused by a heterozygous germline mutation on one allele and a somatic mutation on the other allele of the RB1 gene.\",\"has_predicate\":\"SEPIO:0000146\",\"has_object\":\"SEPIO:0002006\",\"type\":\"SEPIO:0002001\",\"is_specified_by\":\"SEPIO:0002004\",\"has_evidence_with_item\":[{\"type\":\"SEPIO:0000173\",\"source\":\"PMID:2601691\",\"description\":\"Dunn et al. (1989) analyzed 19 patients with RB. RB1 mutations were identified in 13 tumors, including the following germline mutations: 55 bp duplication within exon 10 (truncated protein) and a 10 bp deletion in exon 18 (truncated protein).\"},{\"type\":\"SEPIO:0000173\",\"source\":\"PMID:2594029\",\"description\":\"Yandell et al. (1989) analyzed tumors from 7 patients with RB. RB1 mutations were identified in all tumors, including a de novo germline mutation (ARG445TER).\"},{\"type\":\"SEPIO:0000173\",\"source\":\"PMID:8651278\",\"description\":\"Lohmann et al. (1996) reported on 119 patients with RB and found RB1 mutations in 99 patients (83%). The mutation spectrum included 42% base substitutions, 26% small length alterations, and 15% large deletions.\"}],\"id\":\"http://dx.clinicalgenome.org/entities/ISCA-30006x1-2019-08-13T18:49:43Z\",\"qualified_contribution\":{\"activity_date\":\"2019-08-13T18:49:43Z\",\"realizes\":\"SEPIO:0000331\"},\"has_subject\":{\"id\":\"http://dx.clinicalgenome.org/entities/ISCA-30006x1\",\"has_subject\":{\"has_location\":\"https://www.ncbi.nlm.nih.gov/gene/5925\",\"type\":\"GENO:0000963\",\"has_count\":1},\"has_predicate\":\"GENO:0000840\",\"type\":\"SEPIO:0002003\",\"has_object\":\"OMIM:180200\"}},{\"id\":\"http://dx.clinicalgenome.org/entities/ISCA-30006x3-2019-08-13T18:49:43Z\",\"qualified_contribution\":{\"activity_date\":\"2019-08-13T18:49:43Z\",\"realizes\":\"SEPIO:0000331\"},\"has_subject\":{\"id\":\"http://dx.clinicalgenome.org/entities/ISCA-30006x3\",\"has_subject\":{\"has_location\":\"https://www.ncbi.nlm.nih.gov/gene/5925\",\"type\":\"GENO:0000963\",\"has_count\":3},\"has_predicate\":\"GENO:0000840\",\"type\":\"SEPIO:0002003\",\"has_object\":\"MONDO:0000001\"},\"is_specified_by\":\"SEPIO:0002004\",\"has_object\":\"SEPIO:0002008\",\"has_predicate\":\"SEPIO:0000146\",\"type\":\"SEPIO:0002001\"}]}"),
   (ConsumerRecord. "gene_dosage_sepio_in"
                   0
                   2
                   1576001641422
                   org.apache.kafka.common.record.TimestampType/CREATE_TIME
                   3047257890
                   -1
                   4429
                   nil
                   "{\"@context\":{\"id\":\"@id\",\"type\":\"@type\",\"SEPIO\":\"http://purl.obolibrary.org/obo/SEPIO_\",\"PMID\":\"https://www.ncbi.nlm.nih.gov/pubmed/\",\"BFO\":\"http://purl.obolibrary.org/obo/BFO_\",\"CG\":\"http://dataexchange.clinicalgenome.org/terms/\",\"DC\":\"http://purl.org/dc/elements/1.1/\",\"OMIM\":\"http://purl.obolibrary.org/obo/OMIM_\",\"MONDO\":\"http://purl.obolibrary.org/obo/MONDO_\",\"FALDO\":\"http://biohackathon.org/resource/faldo#\",\"NCBI_NU\":\"https://www.ncbi.nlm.nih.gov/nuccore/\",\"RDFS\":\"http://www.w3.org/2000/01/rdf-schema#\",\"GENO\":\"http://purl.obolibrary.org/obo/GENO_\",\"IAO\":\"http://purl.obolibrary.org/obo/IAO_\",\"DCT\":\"http://purl.org/dc/terms/\",\"has_evidence_with_item\":{\"@id\":\"SEPIO:0000189\",\"@type\":\"@id\"},\"has_predicate\":{\"@id\":\"SEPIO:0000389\",\"@type\":\"@id\"},\"has_subject\":{\"@id\":\"SEPIO:0000388\",\"@type\":\"@id\"},\"has_object\":{\"@id\":\"SEPIO:0000390\",\"@type\":\"@id\"},\"qualified_contribution\":{\"@id\":\"SEPIO:0000159\",\"@type\":\"@id\"},\"is_specified_by\":{\"@id\":\"SEPIO:0000041\",\"@type\":\"@id\"},\"reference\":{\"@id\":\"FALDO:reference\",\"@type\":\"@id\"},\"realizes\":{\"@id\":\"BFO:0000055\",\"@type\":\"@id\"},\"source\":{\"@id\":\"DCT:source\",\"@type\":\"@id\"},\"is_feature_affected_by\":{\"@id\":\"GENO:0000445\",\"@type\":\"@id\"},\"has_part\":{\"@id\":\"BFO:0000051\",\"@type\":\"@id\"},\"is_version_of\":{\"@id\":\"DCT:isVersionOf\",\"@type\":\"@id\"},\"interval\":{\"@id\":\"GENO:0000966\",\"@type\":\"@id\"},\"has_location\":{\"@id\":\"GENO:0000903\",\"@type\":\"@id\"},\"is_about\":{\"@id\":\"IAO:0000136\",\"@type\":\"@id\"},\"label\":\"RDFS:label\",\"activity_date\":\"SEPIO:0000160\",\"has_count\":\"GENO:0000917\",\"start\":\"GENO:0000894\",\"end\":\"GENO:0000895\",\"description\":\"DC:description\",\"sequence_id\":{\"@id\":\"GENO:0000967\",\"@type\":\"@id\"},\"SimpleInterval\":\"GENO:0000965\",\"SequenceLocation\":\"GENO:0000815\"},\"type\":\"SEPIO:0002015\",\"id\":\"http://dx.clinicalgenome.org/entities/ISCA-46285-2019-12-09T19:33:02Z\",\"is_version_of\":\"http://dx.clinicalgenome.org/entities/ISCA-46285\",\"qualified_contribution\":{\"activity_date\":\"2019-12-09T19:33:02Z\",\"realizes\":\"SEPIO:0000331\"},\"is_about\":{\"type\":\"SequenceLocation\",\"id\":\"http://dx.clinicalgenome.org/entities/region-ISCA-46285\",\"label\":\"15q13 recurrent region (BP3-BP4) (includes APBA2)\",\"sequence_id\":\"NCBI_NU:NC_000015.9\",\"interval\":{\"type\":\"SimpleInterval\",\"start\":29156959,\"end\":30368990}},\"has_part\":[]}")])

(def doc-def
  {:name "http://dataexchange.clinicalgenome.org/models/sepio-clingen-dosage-shapes.ttl"
  :source "https://raw.githubusercontent.com/clingen-data-model/SEPIO-ontology/master/src/ontology/extensions/clingen/clingen-dosage/sepio-clingen-dosage-shapes.ttl"
  :target "sepio-clingen-dosage-shapes.ttl"
  :format :rdf
  :reader-opts {:format :turtle}})

(deftest stream-messages-test
  (with-test-database
    ;; seed the knowledge base with the dosage shape we are validating against
    (b/import-document "http://dataexchange.clinicalgenome.org/models/sepio-clingen-dosage-shapes.ttl" (list doc-def))
    (doseq [record good-dosage-records]
      (import-record! record (get-in config [:topics :gene-dosage-stage])))
    ;; useful for when debugging with the rebl
    ;; (def r (q/resource "http://dx.clinicalgenome.org/entities/ISCA-30006-2019-08-13T18:49:43Z"))
    ;; (tap> r)
    (is (= 2 (count (q/select "select ?x where { ?x a :sepio/GeneDosageReport }"))))))
