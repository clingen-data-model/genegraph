(ns genegraph.annotate-test
  (:require [genegraph.annotate :as ann :refer :all]
            [genegraph.database.query :as q]
            [genegraph.database.validation :as validate]
            [genegraph.server-test :refer [mount-database-fixture]]
            [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(use-fixtures :each mount-database-fixture)  

(deftest decorate-event-with-iri
  (let [evt (-> "test_data/gene_validity_0_1094.edn" io/resource slurp edn/read-string)]
    (is (= "http://dataexchange.clinicalgenome.org/gci/proposition_03628749-51d6-437e-9816-1e32852645cf"
           (-> evt add-metadata add-model add-iri ::ann/iri)))))


(deftest actionability-event-handling
  (let [aci-evt (-> "test_data/actionability_0_1186.edn" io/resource slurp edn/read-string)]
    (is (= "https://actionability.clinicalgenome.org/ac/Pediatric/api/sepio/doc/AC005"
           (-> aci-evt add-metadata add-model add-iri ::ann/iri)))))

(deftest dosage-event-handling
  (let [dosage-evt (-> "test_data/gene_dosage_sepio_in_0_201.edn"
                       io/resource
                       slurp
                       edn/read-string)]
    (is (= "http://dx.clinicalgenome.org/entities/ISCA-4799-2019-08-22T18:51:29Z"
           (-> dosage-evt add-metadata add-model add-iri ::ann/iri)))))

(deftest dosage-event-shacl-validation
  (let [gene-based-dosage-data  (-> "test_data/gene_dosage_sepio_in_0_201.edn"
                       io/resource
                       slurp
                       edn/read-string)
        dosage-evt (-> gene-based-dosage-data
                       add-metadata
                       add-model
                       add-iri
                       add-validation-shape
                       add-validation-context
                       add-validation)]
    (is (true? (validate/did-validate? (::ann/validation dosage-evt)))))
  (let [region-based-dosage-data  (-> "test_data/gene_dosage_sepio_in_0_5534.edn"
                       io/resource
                       slurp
                       edn/read-string)
        dosage-evt (-> region-based-dosage-data
                       add-metadata
                       add-model
                       add-iri
                       add-validation-shape
                       add-validation-context
                       add-validation)]
    (is (true? (validate/did-validate? (::ann/validation dosage-evt)))))
  (let [invalid-dosage-data  (-> "test_data/gene_dosage_sepio_in_bad.edn"
                       io/resource
                       slurp
                       edn/read-string)
        dosage-evt (-> invalid-dosage-data add-metadata add-model add-iri add-validation)]
    (is (false? (validate/did-validate? (::ann/validation dosage-evt))))))

(deftest dosage-event-subjects
  (let [dosage-data  (-> "test_data/gene_dosage_sepio_in_0_201.edn"
                       io/resource
                       slurp
                       edn/read-string)
        dosage-evt (-> dosage-data add-metadata add-model add-iri add-subjects)
        subjects (dosage-evt ::ann/subjects)]
    (is (= 1 (count (:gene-iris subjects))))
    (is (= "https://www.ncbi.nlm.nih.gov/gene/4088" (first (:gene-iris subjects))))))

(deftest validity-event-subjects
  (let [evt (-> "test_data/gene_validity_0_1094.edn" io/resource slurp edn/read-string)
        subjects (-> evt add-metadata add-model add-iri add-subjects ::ann/subjects)]
    (is (= 1 (count (:gene-iris subjects))))
    (is (= "https://www.ncbi.nlm.nih.gov/gene/51776" (first (:gene-iris subjects))))
        (is (= 1 (count (:disease-iris subjects))))
    (is (= "http://purl.obolibrary.org/obo/MONDO_0054695" (first (:disease-iris subjects))))))
    
(deftest actionability-event-subjects
  (let [evt (-> "test_data/actionability_0_1186.edn" io/resource slurp edn/read-string)
        subjects (-> evt add-metadata add-model add-iri add-subjects ::ann/subjects)]
    (is (= 1 (count (:gene-iris subjects))))
    (is (= "https://www.ncbi.nlm.nih.gov/gene/55630" (first (:gene-iris subjects))))
    (is (= 1 (count (:disease-iris subjects))))
    (is (= "http://purl.obolibrary.org/obo/MONDO_0008713" (first (:disease-iris subjects))))))

