(ns genegraph.sink.event-test
  (:require [genegraph.sink.event :refer :all]
            [genegraph.transform.core :refer [add-model]]
            [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(deftest decorate-event-with-iri
  (let [evt (-> "test_data/gene_validity_0_1094.edn" io/resource slurp edn/read-string)]
    (is (= "http://dataexchange.clinicalgenome.org/gci/report_03628749-51d6-437e-9816-1e32852645cf-2020-06-08T141802.817Z"
           (-> evt add-metadata add-model add-iri :genegraph.sink.event/iri)))))


(deftest actionability-event-handling
  (let [aci-evt (-> "test_data/actionability_0_1186.edn" io/resource slurp edn/read-string)]
    (is (= "https://actionability.clinicalgenome.org/ac/Pediatric/api/sepio/doc/AC005"
           (-> aci-evt add-metadata add-model add-iri :genegraph.sink.event/iri)))))

(deftest dosage-event-handling
  (let [dosage-evt (-> "test_data/gene_dosage_sepio_in_0_201.edn"
                       io/resource
                       slurp
                       edn/read-string)]
    (is (= "http://dx.clinicalgenome.org/entities/ISCA-4799-2019-08-22T18:51:29Z"
           (-> dosage-evt add-metadata add-model add-iri :genegraph.sink.event/iri)))))
