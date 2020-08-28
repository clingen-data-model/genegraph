(ns genegraph.rocksdb-test
  (:require [genegraph.rocksdb :as sut]
            [clojure.test :refer :all]))

(def test-db-name "test_rocks_instance")

(deftest rocksdb-function-test
  (sut/rocks-destroy! test-db-name)
  (with-open [db (sut/open test-db-name)]
    (testing "single value key"
      (sut/rocks-put! db "key1" "value1")
      (is (= "value1" (sut/rocks-get db "key1"))))
    (testing "multi-value key"
      (sut/rocks-put-multipart-key! db ["keypart1" "keypart2"] "value2")
      (is (= "value2" (sut/rocks-get-multipart-key db ["keypart1" "keypart2"]))))
    (testing "delete single-value key"
      (sut/rocks-delete! db "key1")
      (is (= ::sut/miss (sut/rocks-get db "key1"))))
    (testing "delete multi-value key"
      (sut/rocks-delete-multipart-key! db ["keypart1" "keypart2"])
      (is (= ::sut/miss (sut/rocks-get-multipart-key db ["keypart1" "keypart2"]))))
    (testing "test prefix delete"
      (sut/rocks-put-multipart-key! db ["keyprefix1" "keysuffix1"] "value3")
      (sut/rocks-put-multipart-key! db ["keyprefix1" "keysuffix2"] "value4")
      (sut/rocks-put-multipart-key! db ["keyprefix2" "keysuffix2"] "value5")
      (is (= "value3" (sut/rocks-get-multipart-key db ["keyprefix1" "keysuffix1"])))
      (is (= "value4" (sut/rocks-get-multipart-key db ["keyprefix1" "keysuffix2"])))
      (sut/rocks-delete-with-prefix! db "keyprefix1")
      (is (= ::sut/miss (sut/rocks-get-multipart-key db ["keyprefix1" "keysuffix1"])))
      (is (= ::sut/miss (sut/rocks-get-multipart-key db ["keyprefix1" "keysuffix2"])))
      (is (= "value5" (sut/rocks-get-multipart-key db ["keyprefix2" "keysuffix2"]))))))
