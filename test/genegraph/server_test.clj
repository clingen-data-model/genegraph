(ns genegraph.server-test
  "Soup to nuts tests for tne entire, functioning system."
  (:require [genegraph.server :as sut]
            [clojure.test :as t :refer [deftest testing is]]
            [genegraph.env :as env]
            [genegraph.database.util :refer [with-test-database]]))

(deftest insert-record-test
  (with-test-database
    (with-redefs [env/data-vol (System/getProperty "java.io.tmpdir")]
      )))
