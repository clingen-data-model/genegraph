(ns genegraph.suggest.suggesters-test
  (:require [genegraph.suggest.suggesters :refer :all]
            [genegraph.suggest.infix-suggester :as suggest]
            [genegraph.annotate :refer [add-metadata add-model add-iri add-subjects]]
            [genegraph.database.query :as q]
            [genegraph.database.validation :as validate]
            [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]))

;; Choose a gene/disease that are combined as part of a single
;; dosage message and the suggester indices reflect this at startup.
;; Update the suggester for the gene and disease apriori to have no contexts.
;; Then process the data containing the dosage stream event to bring the
;; suggester indicies up to date.
;; Check that they are updated upon retreival.

;; TODO process-event not in current NS, also test appears to have possible side effects not isolated to test

;; (deftest suggester-update
;;   (let [save-gene-result-map (get-suggester-result-map "https://www.ncbi.nlm.nih.gov/gene/4286" :gene)
;;         save-disease-result-map (get-suggester-result-map "http://purl.obolibrary.org/obo/MONDO_0019517"
;;                                                           :disease)
;;         updated-gene-payload (assoc (:payload save-gene-result-map) :curations #{} :weight 0)
;;         updated-disease-payload (assoc (:payload save-disease-result-map) :curations #{} :weight 0)]
;;     ;; rest both the gene and disease payload to remove contexts
;;     (suggest/update-suggestion (get-suggester :gene)
;;                                (:label updated-gene-payload)
;;                                updated-gene-payload
;;                                (:curations updated-gene-payload)
;;                                (:weight updated-gene-payload))
;;     (suggest/update-suggestion (get-suggester :disease)
;;                                (:label updated-disease-payload)
;;                                updated-disease-payload
;;                                (:curations updated-disease-payload)
;;                                (:weight updated-disease-payload))
;;     (let [dosage-data  (-> "test_data/gene_dosage_sepio_in_contrived.edn"
;;                            io/resource
;;                            slurp
;;                            edn/read-string)]
;;       (-> dosage-data add-metadata add-model add-iri add-subjects process-event)
;;       (let [db-gene-result-map (get-suggester-result-map "https://www.ncbi.nlm.nih.gov/gene/4286" :gene)
;;             db-disease-result-map (get-suggester-result-map "http://purl.obolibrary.org/obo/MONDO_0019517"
;;                                                          :disease)]
;;         (is (= 1 (count (get-in db-gene-result-map [:payload :curations]))))
;;         (is (= #{:GENE_DOSAGE} (get-in db-gene-result-map [:payload :curations])))
;;         (is (= 1 (count (get-in db-disease-result-map [:payload :curations]))))
;;         (is (= #{:GENE_DOSAGE} (get-in db-disease-result-map [:payload :curations])))))))
