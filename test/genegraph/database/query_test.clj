(ns genegraph.database.query-test
  (:require [clojure.test :refer :all]
            [genegraph.database.query :as q :refer :all]
            [genegraph.database.load :refer [statements-to-model]]))


(def sample-data (statements-to-model [["http://test/resource1" :rdf/type :owl/Class]
                                       ["http://test/resource1" :rdfs/label "TestClass"]]))


(deftest test-query-with-local-bindings
  (let [q (create-query "select ?class ?label where
 { ?class a :owl/Class ;
 :rdfs/label ?label }
 limit 1")]
    (is (= "TestClass" (-> (q {::q/model sample-data}) first :label)))))

(deftest test-resource-creation
  (let [iri "http://test/resource1"
        r (resource iri)]
    (is (= iri (str r)))))

(deftest test-construct-query
  (let [result-model (construct "construct {<http://example/resource2> a ?type} where {<http://test/resource1> a ?type}"
                                {}
                                sample-data)
        r (select "select ?x where { ?x a :owl/Class }" {} result-model)]
    (is (seq r))))


(deftest test-model-union
  (let [other-model (statements-to-model [["http://test/resource2" :rdf/type :owl/Class]])
        union-model (union sample-data other-model)]
    (is (= 2 (count (select "select ?x where { ?x a :owl/Class }" {} union-model))))))

(deftest test-data-threading
 (let [nested-sample (statements-to-model [["http://example/baby-class" 
                                             :rdfs/sub-class-of
                                             (resource "http://example/mama-class")]
                                            ["http://example/baby-class" 
                                             :rdfs/label
                                             "I'm the baby!"]
                                            ["http://example/mama-class" 
                                             :rdfs/sub-class-of
                                             (resource "http://example/nana-class")]
                                            ["http://example/mama-class" 
                                             :rdfs/label
                                             "I'm the mama!"]
                                            ["http://example/nana-class" 
                                             :rdfs/label
                                             "I'm the nana!"]])
        baby-class (first (select "select ?x where { ?x :rdfs/label ?name }" {:name "I'm the baby!"} nested-sample))
        mama-class (first (select "select ?x where { ?x :rdfs/label ?name }" {:name "I'm the mama!"} nested-sample))
        nana-class (first (select "select ?x where { ?x :rdfs/label ?name }" {:name "I'm the nana!"} nested-sample))]
    (testing "testing ld->"
      (is (= 1 (count (ld-> baby-class [:rdfs/sub-class-of :rdfs/sub-class-of])))))
    (testing "testing ld1->"
      (is (= "http://example/nana-class" (str (ld1-> baby-class [:rdfs/sub-class-of :rdfs/sub-class-of])))))
    (testing "testing outward map-like access"
      (is (seq (:rdfs/sub-class-of baby-class))))
    (testing "testing undirected map-like access"
      (is (= 2 (count (get mama-class [:rdfs/sub-class-of :-])))))
    (testing "testing inward map-like access"
      (is (= 1 (count (get nana-class [:rdfs/sub-class-of :<])))))))

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

(deftest test-text-search-bgp
  ;; this produces a construct that it a little hard to inspect, but we can at least validate
  ;; the number of statements that are produced
  (is (= 5 (count (text-search-bgp 'x :cg/resource 'text)))))

(deftest test-to-ref
  (is (= :owl/Class (-> :owl/Class resource to-ref))))

(deftest test-is-rdf-type
  (let [r (first (select "select ?x where { ?x a :owl/Class }" {} sample-data))]
    (is (is-rdf-type? r :owl/Class))
    (is (not (is-rdf-type? r :rdfs/Datatype)))))

;; This is a fn that 
;; 1) doesn't work as intended
;; 2) doesn't belong here anyway 
;; 3) may not be needed at all depending
;; This is a light-touch test (does it even return a string?) used mostly as a bridge to either
;; removing it altogether or properly refactoring (and repairing) it
(deftest test-path
  (is (string? (-> :owl/Class resource path))))

(deftest test-to-turtle
  (is (= 
       (to-turtle sample-data)
       "<http://test/resource1>\n        a       <http://www.w3.org/2002/07/owl#Class> ;\n        <http://www.w3.org/2000/01/rdf-schema#label>\n                \"TestClass\" .\n")))
