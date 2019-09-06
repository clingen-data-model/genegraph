(ns genegraph.sink.base-test
  (:require [clojure.test :refer :all]
            [genegraph.sink.base :as base :refer :all])
  (:import [org.apache.jena.tdb2 TDB2Factory]))

(deftest import-base-data-test
  (with-redefs [genegraph.database.instance/db (TDB2Factory/createDataset)
                base/target-base "test-data/base/"
                base/base-resources "base_test.edn"]
    (import-base-data (read-base-resources))))


