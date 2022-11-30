(ns genegraph.transform.clinvar.hgvs-test
  (:require [clojure.test :as test]
            [genegraph.transform.clinvar.hgvs :as hgvs]))

(defn m<=
  "Map subset. Returns true if the keys and values of m1 also exist in m2"
  [m1 m2]
  (= m1 (select-keys m2 (keys m1))))

(test/deftest test-parse-sequence-and-location
  (test/testing "Trivial cases"
    (test/is (nil? (hgvs/hgvs-parse-sequence-and-location "")))
    (test/is (nil? (hgvs/hgvs-parse-sequence-and-location nil))))
  (test/testing "Totally invalid"
    (test/is (nil? (hgvs/hgvs-parse-sequence-and-location "notanexpression"))))
  ;; Might want to add some partially invalid ones, and define behavior for that
  (test/testing "Simple dup"
    (let [expr "NC_1.1:g.100_101dup"
          expected {:accession "NC_1.1"
                    :sequence-type "g"
                    :start 100
                    :end 101
                    :remainder "dup"}]
      (test/is (m<= expected (hgvs/hgvs-parse-sequence-and-location expr)))))
  (test/testing "Simple del"
    (let [expr "NC_1.1:g.100_101del"
          expected {:accession "NC_1.1"
                    :sequence-type "g"
                    :start 100
                    :end 101
                    :remainder "del"}]
      (test/is (m<= expected (hgvs/hgvs-parse-sequence-and-location expr)))))
  (test/testing "del with definite range"
    (let [expr "NC_1.1:g.(100_110)_(120_130)del"
          expected {:accession "NC_1.1"
                    :sequence-type "g"
                    :start [100 110]
                    :end [120 130]
                    :remainder "del"}]
      (test/is (m<= expected (hgvs/hgvs-parse-sequence-and-location expr)))))
  (test/testing "dup with indefinite outer"
    (let [expr "NC_1.1:g.(?_100)_(110_?)dup"
          expected {:accession "NC_1.1"
                    :sequence-type "g"
                    :start ["?" 100]
                    :end [110 "?"]
                    :remainder "dup"}]
      (test/is (m<= expected (hgvs/hgvs-parse-sequence-and-location expr)))))
  (test/testing "dup with indefinite inner"
    (let [expr "NC_1.1:g.(100_?)_(?_110)dup"
          expected {:accession "NC_1.1"
                    :sequence-type "g"
                    :start [100 "?"]
                    :end ["?" 110]
                    :remainder "dup"}]
      (test/is (m<= expected (hgvs/hgvs-parse-sequence-and-location expr)))))
  (test/testing "Real examples"
    (let [[expr1 expr2 expr3]
          ["NC_000003.12:g.177772523_185716872dup"
           "NC_000017.10:g.(?_34508117)_(36248918_?)dup"
           "NC_000021.7:g.(40550036_40589822)_(46915388_46944323)del"]
          expected1 {:accession "NC_000003.12"
                     :sequence-type "g"
                     :start 177772523
                     :end 185716872
                     :remainder "dup"}
          expected2 {:accession "NC_000017.10"
                     :sequence-type "g"
                     :start ["?" 34508117]
                     :end [36248918 "?"]
                     :remainder "dup"}
          expected3 {:accession "NC_000021.7"
                     :sequence-type "g"
                     :start [40550036 40589822]
                     :end [46915388 46944323]
                     :remainder "del"}]
      (test/is (m<= expected1 (hgvs/hgvs-parse-sequence-and-location expr1)))
      (test/is (m<= expected2 (hgvs/hgvs-parse-sequence-and-location expr2)))
      (test/is (m<= expected3 (hgvs/hgvs-parse-sequence-and-location expr3))))))


(test/deftest test-parsed-expression-span
  (test/testing "Normal case single bounds"
    (test/is (= 11 (hgvs/parsed-expression-span
                    {:start 10 :end 20}))))
  (test/testing "Normal case definite range bounds"
    (test/is (= 11 (hgvs/parsed-expression-span
                    {:start [10 11] :end [19 20]}))))
  (test/testing "Normal case single and definite range"
    (test/is (= 11 (hgvs/parsed-expression-span
                    {:start [10 11] :end 20})))
    (test/is (= 11 (hgvs/parsed-expression-span
                    {:start 10 :end [19 20]}))))
  (test/testing "Missing endpoint"
    (test/is (= 0 (hgvs/parsed-expression-span
                   {:start 10})))
    (test/is (= 0 (hgvs/parsed-expression-span
                   {:end 20}))))
  (test/testing "Indefinite endpoint"
    (test/is (= 11 (hgvs/parsed-expression-span
                    {:start ["?" 10] :end [20 "?"]})))
    (test/is (= 11 (hgvs/parsed-expression-span
                    {:start [10 "?"] :end ["?" 20]}))))
  (test/testing "Unusably indefinite endpoint"
    (test/is (= 0 (hgvs/parsed-expression-span
                   {:start ["?" "?"] :end ["?" 20]})))
    (test/is (= 0 (hgvs/parsed-expression-span
                   {:start ["?" 10] :end ["?" "?"]})))))


#_(test/run-tests)


(def expressions
  ["NC_000003.12:g.177772523_185716872dup"
   "NC_000017.10:g.(?_34508117)_(36248918_?)dup"
   "NC_000021.7:g.(40550036_40589822)_(46915388_46944323)del"])

(def totally-invalid
  ["asdf"])

(comment
  (clojure.pprint/pprint
   (for [expr expressions]
     (hgvs/hgvs-parse-sequence-and-location expr)))

  (clojure.pprint/pprint
   (for [expr totally-invalid]
     (hgvs/hgvs-parse-sequence-and-location expr))))
