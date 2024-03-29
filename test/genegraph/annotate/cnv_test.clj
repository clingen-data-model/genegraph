(ns genegraph.annotate.cnv_test
  "Test copy number variation parsing."
  (:require [clojure.test            :refer [is deftest testing]]
            [clojure.spec.alpha      :as s]
            [clojure.spec.test.alpha :as stest]
            [genegraph.annotate.cnv  :as cnv]))

(def ^:private examples
  "Examples of CNV string syntax."
  ["GRCh37/hg19 13q31.3(chr13:93244802-93269486)x0"
   "GRCh37/hg19 1q21.1(chr1:143134063-143284670)x3"
   "GRCh37/hg19 Xp22.33(chrX:697169-1238257)x0"
   "GRCh37/hg19 Yp11.32(chrY:21267-39498)x0"
   "GRCh38/hg38 1p36.33(chr1:1029317-1072906)x4"
   "GRCh38/hg38 6q16.1-16.2(chr6:98770647-99813111)x1"
   "NCBI36/hg18 Xq21.31(chrX:88399122-88520760)x1"])

(stest/instrument `cnv/parse)
(stest/instrument `cnv/unparse)

(deftest satisfy-examples
  (testing "round-trip all the example strings"
    (is (= examples (map (comp cnv/unparse cnv/parse) examples)))))

(deftest output-spec
  (testing "the ::cnv spec is correct."
    (is (->> examples
             (map (comp (partial s/valid? ::cnv/cnv) cnv/parse))
             (every? true?))))
  (testing "nil is not a ::cnv"
    (is (not (s/valid? ::cnv/cnv nil)))))

(defn round-trip-ok?
  "True when EXPECTED equals round-tripped INPUT ignoring ::cnv/string."
  [[expected input]]
  (letfn [(unstring [s] (dissoc s ::cnv/string))]
    (= (unstring expected)
       (-> input cnv/unparse cnv/parse unstring))))

(deftest generated-properties
  (testing "round-trip with generated properties"
    (is (-> ::cnv/cnv s/exercise
            (->> (map round-trip-ok?)
                 (every? true?))))))

(defn check-ok?
  "True when SYNTAX-QUOTED-FN pass?es all of its tests."
  [syntax-quoted-fn]
  (-> syntax-quoted-fn stest/check
      (->> (map (comp :pass? :clojure.spec.test.check/ret))
           (every? true?))))

(deftest check
  (testing "with spec's test check"
    (is (check-ok? `cnv/parse))
    (is (check-ok? `cnv/unparse))))
