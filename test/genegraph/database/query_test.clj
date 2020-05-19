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

(deftest test-count-query
  (let [q (create-query '[:project [x]
                          [:bgp [x :rdf/type :owl/Class]]])]
    (is (= 1 (q {::q/model sample-data ::q/params {:type :count}})))))

(deftest test-string-query
  (let [result (select "select ?x where { ?x a :owl/Class } " {::q/model sample-data})]
    (is (= 1 (count result)))))

(deftest test-ask-query 
  (let [q (create-query "ask { ?x a :owl/Class }")]
    (is (= true (q)))))

(deftest test-ask-with-algebra
  (let [q (create-query '[:bgp [x :rdf/type :owl/Class]] {::q/type :ask})]
    (is (= true (q)))))

(def union-sample (statements-to-model [["http://test/report1" :rdf/type :sepio/GeneDosageReport]
                                        ["http://test/report2" :rdf/type :sepio/GeneValidityReport]
                                        ["http://test/report3" :rdf/type :sepio/ActionabilityReport]
                                        ]))

(deftest test-algebra-union
  (let [q (create-query '[:project [x]
                          [:union
                           [:bgp [x :rdf/type :sepio/GeneDosageReport]]
                           [:bgp [x :rdf/type :sepio/GeneValidityReport]]
                           [:bgp [x :rdf/type :sepio/ActionabilityReport]]]])]
    (is (= 3 (count (q {::q/model union-sample}))))))
