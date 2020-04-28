(ns genegraph.database.query-test
  (:require [clojure.test :refer :all]
            [genegraph.database.query :as q :refer :all]
            [genegraph.database.load :refer [statements-to-model]]))


(def sample-data (statements-to-model [["http://test/resource1" :rdf/type :owl/Class]
                                       ["http://test/resource1" :rdfs/label "TestClass"]]))


(deftest test-query-algebra-build
  (let [q (create-query '[:project [x]
                          [:bgp [x :rdf/type :owl/Class]]])]
    (is (= 1 (count (q {::q/model sample-data}))))))

(deftest test-string-query
  (let [result (select "select ?x where { ?x a :owl/Class } " {::q/model sample-data})]
    (is (= 1 (count result)))))
