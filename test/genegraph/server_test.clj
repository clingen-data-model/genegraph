(ns genegraph.server-test
  "Soup to nuts tests for tne entire, functioning system."
  (:require [genegraph.server :as sut]
            [genegraph.service :as service]
            [io.pedestal.http :as server]
            [clojure.test :as t :refer [deftest testing is use-fixtures]]
            [genegraph.env :as env]
            [genegraph.database.util :refer [with-test-database]]
            [genegraph.sink.event :as event]
            [genegraph.source.graphql.core :as gql :refer [gql-query]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [mount.core :as mount]
            [io.pedestal.test :refer [response-for]]
            [cheshire.core :as json])
  (:import [java.io PushbackReader]))

(defn mount-database-fixture [f]
  (let [data-vol (str (System/getProperty "java.io.tmpdir") (java.util.UUID/randomUUID))]
    (fs/mkdir data-vol)
    (mount/stop)
    (with-test-database
      (with-redefs [env/data-vol data-vol]
        (mount.core/start-without #'genegraph.sink.stream/consumer-thread
                                  #'genegraph.server/server)
        (f)
        (mount.core/stop)))
    (fs/delete-dir data-vol)))

(use-fixtures :each mount-database-fixture)

(def gv-assertions-query 
"{
  gene_validity_assertions {
    curation_list {
      classification {
        label
      }
    }
    count
  }
}")

(deftest event-lifecycle-test
  (with-open [r (io/reader (io/resource "test_data/test_events.edn"))]
    (let [events (edn/read (PushbackReader. r))
          server (::server/service-fn (server/create-servlet (service/service)))]
      (doseq [base-event (:base-data events)]
        (event/process-event! base-event))
      (event/process-event! (:hgnc-genes events))
      (event/process-event! (:mondo-diseases events))
      (event/process-event! (:publish-gv-curation events))
      (testing "Test simple genes query"
        (let [query "{genes {gene_list {label iri} count}}"
              response (response-for server
                                     :post "/graphql"
                                     :headers {"Content-Type" "application/json"}
                                     :body (json/generate-string {:query query}))
              body (json/parse-string (:body response) true)
              gene-set (into #{} (map :iri (->> body :data :genes :gene_list)))]
          (is (< 0 (count gene-set)))
           (doseq [gene (:curated-genes events)]
             (is (gene-set gene)))))
      (testing "Test gene-validity curation query"
        (let [response (response-for server
                                     :post "/graphql"
                                     :headers {"Content-Type" "application/json"}
                                     :body (json/generate-string {:query gv-assertions-query}))
              body (json/parse-string (:body response) true)]
          (clojure.pprint/pprint body)
          (is (< 0 (get-in body [:data :gene_validity_assertions :count]))))))))
