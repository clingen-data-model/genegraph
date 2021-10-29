(ns genegraph.sink.event-test
  (:require [genegraph.annotate :as ann :refer [add-metadata add-iri add-validation]]
            [genegraph.sink.event :refer :all]
            [genegraph.transform.core :refer [add-model]]
            [genegraph.server-test :refer [mount-database-fixture]]
            [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(use-fixtures :once mount-database-fixture)

;; TODO - TON - Test Failing due to no ::graph-name entry in add-iri - discuss with Tristan
(deftest decorate-event-with-iri
  (let [evt (-> "test_data/gene_validity_0_1094.edn" io/resource slurp edn/read-string)]
    (is (= "http://dataexchange.clinicalgenome.org/gci/proposition_03628749-51d6-437e-9816-1e32852645cf"
           (-> evt add-metadata add-model add-iri ::ann/iri)))))

;; TODO - TON - Test Failing due to no ::graph-name entry in add-iri - discuss with Tristan
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
