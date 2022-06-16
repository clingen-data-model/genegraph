(ns genegraph.transform.clinvar.variation-test
  (:require [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.transform.clinvar.variation :as variation]
            [genegraph.transform.clinvar.util :as util :refer [str->bytestream]]
            [genegraph.transform.types :as xform-types]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.database.names :refer [local-property-names
                                              local-class-names
                                              property-uri->keyword
                                              prefix-ns-map]]
            [genegraph.annotate :as ann]
            [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            ;[genegraph.server-test :refer [mount-database-fixture]]
            [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [io.pedestal.log :as log]
            [clojure.string :as s])
  (:import (java.time LocalDate)
           (org.apache.jena.rdf.model Model)
           (clojure.lang Keyword)))

(defn fixtures [f]
  (mount.core/start #'genegraph.database.instance/db #'genegraph.database.property-store/property-store)
  (f))

(use-fixtures :once fixtures)
;(use-fixtures :each mount-database-fixture)


(defn eventify [input-map]
  {:genegraph.sink.event/value (json/generate-string input-map)
   :genegraph.transform.core/format :clinvar-raw})

(defn to-events [values]
  (map eventify values))

(defn add-models [events]
  (map xform-types/add-model events))

(defn add-model-jsonlds [events]
  (map xform-types/add-model-jsonld events))

(defn triple-sanity-check [triples]
  (for [triple triples]
    (do
      (log/debug :triple triple)
      (assert (sequential? triple) "Triple must be a sequential")
      (assert (= 3 (count triple)) "Triple must have 3 elements")
      (assert (util/in? [org.apache.jena.rdf.model.impl.ResourceImpl
                         genegraph.database.query.types.RDFResource
                         String]
                        (class (first triple)))
              (str "First value must be resource or string, was " (class (first triple))))
      (assert (util/in? [genegraph.database.query.types.RDFResource Keyword]
                        (class (second triple)))
              (str "Second value must be resource or property keyword " triple))
      (assert (not (sequential? (nth triple 2)))
              (str "Third value must be single value " triple))
      triple)))

(deftest test-prioritized-variation-expression
  (testing "Trivial cases"
    (let [variant-no-content {}
          variant-no-nested-content {:content {}}
          variant-no-hgvslist {:content {:content {}}}
          variant-empty-hgvslist {:content {:content (json/generate-string
                                                       {"HGVSlist" {}})}}
          variant-empty-hgvs-array {:content {:content (json/generate-string
                                                         {"HGVSlist" {"HGVS" []}})}}]
      (test/is (nil? (variation/prioritized-variation-expression variant-no-content)))
      (test/is (nil? (variation/prioritized-variation-expression variant-no-nested-content)))
      (test/is (nil? (variation/prioritized-variation-expression variant-no-hgvslist)))
      (test/is (nil? (variation/prioritized-variation-expression variant-empty-hgvslist)))
      (test/is (nil? (variation/prioritized-variation-expression variant-empty-hgvs-array)))))

  (testing "Invalid SPDI, but valid GRCh38"
    ; Has CanonicalSPDI field but no $ value for it, so continues to look at HGVS ones
    (let [variant-invalid-spdi {:content {:content (json/generate-string
                                                     {"CanonicalSPDI" {"somefield" "somevalue"}
                                                      "HGVSlist" {"HGVS" [{"NucleotideExpression" {"@Assembly" "GRCh38"
                                                                                                   "Expression" {"$" "NE38"}}}
                                                                          {"NucleotideExpression" {"@Assembly" "GRCh37"
                                                                                                   "Expression" {"$" "NE37"}}}
                                                                          {"ProteinExpression" {"Expression" {"$" "PE1"}}}]}})}}]
      (test/is (= {:expr "NE38", :type :hgvs} (variation/prioritized-variation-expression variant-invalid-spdi)))))

  (testing "Invalid SPDI and GRCh38, valid GRCh37"
    (let [variant {:content {:content (json/generate-string
                                        {"CanonicalSPDI" {"somefield" "somevalue"}
                                         "HGVSlist" {"HGVS" [{"NucleotideExpression" {"@Assembly" "GRCh38"
                                                                                      "Expression" {"somefield" "somevalue"}}}
                                                             {"NucleotideExpression" {"@Assembly" "GRCh37"
                                                                                      "Expression" {"$" "NE37"}}}
                                                             {"ProteinExpression" {"Expression" {"$" "PE1"}}}]}})}}]
      (test/is (= {:expr "NE37", :type :hgvs} (variation/prioritized-variation-expression variant)))))

  (testing "Normal expression cases"
    (let [variant-37 {:content {:content (json/generate-string
                                           {"HGVSlist" {"HGVS" [{"NucleotideExpression" {"@Assembly" "GRCh37"
                                                                                         "Expression" {"$" "NE37"}}}
                                                                {"ProteinExpression" {"Expression" {"$" "PE1"}}}]}})}}
          variant-38 {:content {:content (json/generate-string
                                           {"HGVSlist" {"HGVS" [{"NucleotideExpression" {"@Assembly" "GRCh38"
                                                                                         "Expression" {"$" "NE38"}}}
                                                                {"NucleotideExpression" {"@Assembly" "GRCh37"
                                                                                         "Expression" {"$" "NE37"}}}
                                                                {"ProteinExpression" {"Expression" {"$" "PE1"}}}]}})}}
          variant-spdi {:content {:content (json/generate-string
                                             {"CanonicalSPDI" {"$" "SPDI1"}
                                              "HGVSlist" {"HGVS" [{"NucleotideExpression" {"@Assembly" "GRCh38"
                                                                                           "Expression" {"$" "NE38"}}}
                                                                  {"NucleotideExpression" {"@Assembly" "GRCh37"
                                                                                           "Expression" {"$" "NE37"}}}
                                                                  {"ProteinExpression" {"Expression" {"$" "PE1"}}}]}})}}
          variant-parsed-content {:content {:content
                                            {"CanonicalSPDI" {"$" "SPDI1"}
                                             "HGVSlist" {"HGVS" [{"NucleotideExpression" {"@Assembly" "GRCh38"
                                                                                          "Expression" {"$" "NE38"}}}
                                                                 {"NucleotideExpression" {"@Assembly" "GRCh37"
                                                                                          "Expression" {"$" "NE37"}}}
                                                                 {"ProteinExpression" {"Expression" {"$" "PE1"}}}]}}}}]
      (test/is (= {:expr "NE37" :type :hgvs} (variation/prioritized-variation-expression variant-37)))
      (test/is (= {:expr "NE38" :type :hgvs} (variation/prioritized-variation-expression variant-38)))
      (test/is (= {:expr "SPDI1" :type :spdi} (variation/prioritized-variation-expression variant-spdi)))
      (test/is (= {:expr "SPDI1" :type :spdi} (variation/prioritized-variation-expression variant-parsed-content)))
      )))


(deftest test-remove-triple
  (let [input-statements1 (let [r (q/resource "http://example.org/R1")]
                            [[r :sepio/has-object (q/resource "http://example.org/variant1")]
                             [r :owl/version-info "2000-01-01"]])]
    (testing "Trivial case removing empty things from a model"
      (let [model (l/statements-to-model input-statements1)]
        ;(test/is (thrown? ()))
        )))

  (testing "Testing that a triple can be removed from a model"
    (let [])))


(defn map-group-by
  "Takes a sequence of maps, turns into a map of val to seqs of maps,
  keyed by values for fieldname.
  [{:a 1} {:a 1} {:a 3} {:a 2}]
  ->
  {1 [{:a 1} {:a 1}]
   2 [{:a 2}]
   3 [{:a 3}]"
  [maps fieldname]
  (->> maps
       (map #(vector (get % fieldname) %))
       (sort-by first)
       (partition-by first)
       (map #(vector (-> % first first)
                     (->> % (map second))))
       (into {})))

(defn write-variants-to-files
  "Takes a seq of maps of jsonld compacted variant records"
  [variants]
  (let [out-dir "variants"]
    (io/delete-file out-dir true)
    ; Take out the version, partition by it, put into map
    ; {version -> [variants]}
    (let [vs (map-group-by variants "version")]
      (log/info :variants-count (count (flatten (map second vs))))
      (doseq [[version variants] vs]
        (log/info :version version :variants-count (count variants))
        (let [fname (str out-dir "/" version ".txt")]
          (io/make-parents fname)
          (spit fname (str (s/join "\n" (map json/generate-string variants)) "\n"))))
      )))
