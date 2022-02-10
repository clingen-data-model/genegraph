(ns genegraph.transform.jsonld.common-test
  (:require [genegraph.database.load :as l]
            [genegraph.transform.jsonld.common :refer :all]
            [clojure.test :as t :refer [deftest testing is]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.data :refer [diff]]
            [io.pedestal.log :as log])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset Charset)))

(defn get-variant-messages []
  (-> "genegraph/transform/clinvar/variants-with-spdi.txt"
      io/resource
      slurp
      (clojure.string/split #"\n")
      (->> (map #(json/parse-string % true)))))

(defn get-variant-messages-by-id [id]
  (filter #(= id (str (:id (:content %)))) (get-variant-messages)))

(defn string->InputStream [s]
  (ByteArrayInputStream. (.getBytes s)))
(def basename "http://example.org/")
(defn ns-term [term] (str basename term))

(defn jsonld-map-to-model [m]
  (l/read-rdf (string->InputStream (json/generate-string m)) {:format :json-ld}))


(def j1 {"@id" "http://example.org/j1"
         "@type" "http://example.org/Type1"
         "http://example.org/field1" "value1"
         "http://example.org/field2" "value2"
         "http://example.org/field3" {"@id" "http://example.org/j4"
                                      "http://example.org/field4" "value4"}})
(def j1-frame {"@type" "http://example.org/Type1"})

(deftest test-model-to-jsonld
  (let [model (jsonld-map-to-model j1)]
    (testing "Testing unframed output"
      (let [expected {"@graph" [{"@id" "http://example.org/j1"
                                 "@type" "http://example.org/Type1"
                                 "http://example.org/field1" "value1"
                                 "http://example.org/field2" "value2"
                                 "http://example.org/field3" {"@id" "http://example.org/j4"}}
                                {"@id" "http://example.org/j4"
                                 "http://example.org/field4" "value4"}],
                      "@id" "BLANK"}]
        (is (= expected (json/parse-string (model-to-jsonld model))))))))

(deftest test-model-to-jsonld-framed
  (let [model (jsonld-map-to-model j1)]
    (testing "Testing framed output"
      (let [expected {"@id" "http://example.org/j1",
                      "@type" "http://example.org/Type1",
                      "http://example.org/field1" "value1",
                      "http://example.org/field2" "value2"
                      "http://example.org/field3" {"@id" "http://example.org/j4"
                                                   "http://example.org/field4" "value4"}}]
        (is (= expected (json/parse-string
                          (jsonld-to-jsonld-framed (model-to-jsonld model)
                                                   (json/generate-string j1-frame)))))))))

(deftest test-json-compact
  (testing "Testing normal case of non-identifier values"
    (let [context {"@context" {"field1" {"@id" "http://example.org/field1"}
                               "field2" {"@id" "http://example.org/field2"}
                               "field3" {"@id" "http://example.org/field3"}}}
          expected {"@id" "http://example.org/j1"
                    "@type" "http://example.org/Type1",
                    "field1" "value1",
                    "field2" "value2",
                    "field3" {"@id" "http://example.org/j4",
                              "http://example.org/field4" "value4"},
                    "@context" {"field1" {"@id" "http://example.org/field1"},
                                "field2" {"@id" "http://example.org/field2"},
                                "field3" {"@id" "http://example.org/field3"}}}]
      (is (= expected (json/parse-string
                        (-> j1
                            (jsonld-map-to-model)
                            (model-to-jsonld)
                            (jsonld-to-jsonld-framed (json/generate-string j1-frame))
                            (jsonld-compact (json/generate-string context))))))))

  ;TODO implement narrower cases
  (letfn []
    (testing "Fully qualified URI as value, not compacted when not an identifier"
      (let [j2 {"@id" "http://example.org/j2"
                "@type" "http://example.org/Type2"
                "http://example.org/field1" "http://example.org/value1"}
            j2-frame {"@type" "http://example.org/Type2"}
            j2-context {"@context" {"field1" {"@id" "http://example.org/field1"
                                              "@type" "@id"}}}
            expected {"@id" "http://example.org/j2"
                      "@type" "http://example.org/Type2"
                      "http://example.org/field1" "http://example.org/value1",
                      "@context" {"field1" {"@id" "http://example.org/field1"}}}
            actual (json/parse-string
                     (-> j2
                         (jsonld-map-to-model)
                         (model-to-jsonld)
                         (jsonld-to-jsonld-framed (json/generate-string j2-frame))
                         (jsonld-compact (json/generate-string j2-context))))
            ]
        (is (= expected actual) (diff expected actual))
        )))




  (comment (testing "Testing case with identifier "
     (let [j2 {"@id" "http://example.org/j2"
               "@type" "http://example.org/Type2"
               "http://example.org/field1" "http://example.org/value1"
               "http://example.org/field2" "http://example.org/value2"}
           j2-model (jsonld-map-to-model j2)
           j2-frame {"@type" "http://example.org/Type2"}
           j2-context {"@context" {"field1" {"@id" "http://example.org/field1"}
                                   "Type2" {"@id" "http://example.org/Type2"}}}

           expected {"@context" {"field1" {"@id" "http://example.org/field1"}}
                     "@id" "http://example.org/j2"
                     "@type" "http://example.org/Type2"
                     "field1" {"@id" "http://example.org/value1"}
                     "http://example.org/field2" "http://example.org/value2"}
           ]
       (is (= expected (json/parse-string
                         (jsonld-compact
                           (jsonld-to-jsonld-framed (model-to-jsonld j2-model)
                                                    (json/generate-string j2-frame))
                           (json/generate-string j2-context))))))))

  (comment (testing "Testing cases with identifier values"
     (let [j2 {"@id" "http://example.org/j2"
               "@type" "http://example.org/Type2"
               "http://example.org/field1" {"@id" "http://example.org/value1"}
               "http://example.org/field2" "http://example.org/value2"}
           j2-model (jsonld-map-to-model j2)
           j2-frame {"@type" "http://example.org/Type2"}
           j2-context {"@context" {"field1" {"@id" "http://example.org/field1"}
                                   "Type2" {"@id" "http://example.org/Type2"}}}

           expected {"@context" {"field1" {"@id" "http://example.org/field1"}}
                     "@id" "http://example.org/j2"
                     "@type" "http://example.org/Type2"
                     "field1" {"@id" "http://example.org/value1"}
                     "http://example.org/field2" "http://example.org/value2"}
           ]
       (is (= expected (json/parse-string
                         (jsonld-compact
                           (jsonld-to-jsonld-framed (model-to-jsonld j2-model)
                                                    (json/generate-string j2-frame))
                           (json/generate-string j2-context)))))))))

