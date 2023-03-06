(ns genegraph.transform.gene-validity-refactor-test
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [genegraph.transform.gene-validity-refactor :as gvr]))

(def json-data (json/parse-string "{ \"clinvarVariantTitle\": \"\",
                                        \"associatedPathogenicities\": [],
                                        \"uuid\": \"c516a2ff-c6d5-40aa-9bd7-e11d1f58d4d7\",
                                        \"dbSNPIds\": [],
                                        \"status\": \"in progress\",
                                        \"clinvarVariantId\": \"\",
                                        \"clinVarSCVs\": [],
                                        \"clinVarRCVs\": [],
                                        \"variation_type\": \"\",
                                        \"schema_version\": \"3\",
                                        \"source\": \"ClinGen AR\",
                                        \"carId\": \"CA429217831\" }" ))

(deftest test-remove-keys-when-empty
  (testing "Non-exisiting keys"
    (is (= 12 (-> (gvr/remove-keys-when-empty json-data ["non-key"])
                  keys
                  count)))
    (is (= 12 (-> (gvr/remove-keys-when-empty json-data ["non-key-1" "non-key-2"])
                  keys
                  count))))
  (testing "Exisiting keys that are not empty"
    (let [results (gvr/remove-keys-when-empty json-data ["carId" "source"])]
      (is (not (nil? (get results "carId"))))
      (is (not (nil? (get results "source"))))))

  (testing "Existing keys that are empty"
    (let [results (gvr/remove-keys-when-empty json-data ["variation_type" "clinvarVariantTitle"])]
      (is (= 10 (-> results keys count)))
      (is (nil? (get results "variation_type")))
      (is (nil? (get results "clinvarVariantTitle")))))

  (testing "Testing empty arrays"
    (let [results (gvr/remove-keys-when-empty json-data ["dbSNPIds" "clinVarSCVs"])]
      (is (= 10 (-> results keys count)))
      (is (nil? (get results "dbSNPids")))
      (is (nil? (get results "clinVarSCVs")))))

  (testing "Testing everything"
    (let [results (gvr/remove-keys-when-empty json-data ["uuid"
                                                         "dbSNPIds"
                                                         "clinvarVariantTitle"
                                                         "schema_version"
                                                         "variant_type"])]
      (is (= 10 (-> results keys count)))
      (is (not (nil? (get results "uuid"))))
      (is (nil? (get results "dbSNPids")))
      (is (nil? (get results "clinVariantTitle")))
      (is (not (nil? (get results "schema_version"))))
      (is (nil? (get results "variant_type")))))

  (testing "Testing other json types that should just be returned"
    (is (= "String" (gvr/remove-keys-when-empty "String" ["foo"])))
    (is (= [:a] (gvr/remove-keys-when-empty [:a] [])))
    (is (not (nil? (gvr/remove-keys-when-empty {} [])))))
  )
                                                      
