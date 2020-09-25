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
            [genegraph.annotate :as ann]
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

(def genes-query
"{
  genes {
    gene_list {
      label
      iri
    }
    count
  }
}")

(def genes-with-validity-query
"{
  genes(curation_activity: GENE_VALIDITY) {
    gene_list {
      label
      iri
    }
    count
  }
}")

(def gene-query
"query Gene($iri: String) {
  gene(iri: $iri) {
    label
    curation_activities
    genetic_conditions {
      disease {
        label
        iri
      }
      gene_validity_assertions {
        classification {
          label
          iri
        }
        report_date
      }
    }
  }
}")

(defn annotate-event [event]
  (-> event
      ann/add-metadata
      ann/add-model
      ann/add-iri
      ann/add-validation
      ann/add-subjects))

(deftest event-lifecycle-test
  (with-open [r (io/reader (io/resource "test_data/test_events.edn"))]
    (let [events (edn/read (PushbackReader. r))
          server (::server/service-fn (server/create-servlet (service/service)))
          query (fn [request variables]
                  (response-for server
                                :post "/graphql"
                                :headers {"Content-Type" "application/json"}
                                :body (json/generate-string {:query request :variables variables})))]
      (doseq [base-event (:base-data events)]
        (event/process-event! base-event))
      (event/process-event! (:hgnc-genes events))
      (event/process-event! (:mondo-diseases events))
      (event/process-event! (:publish-gv-curation events))
      (testing "Test simple genes query"
        (let [response (query genes-query {})
              body (json/parse-string (:body response) true)
              gene-set (into #{} (map :iri (->> body :data :genes :gene_list)))]
          (is (< 0 (count gene-set)))
           (doseq [gene (:curated-genes events)]
             (is (gene-set gene)))))
      (testing "Test gene-validity curation query"
        (let [response (query gv-assertions-query {})
              body (json/parse-string (:body response) true)]
          (is (< 0 (get-in body [:data :gene_validity_assertions :count])))))
      (testing "Test cache expiration"
        (println (-> events :gene-validity-update-sequence first keys ))
        (let [evt-with-annotation (-> events :gene-validity-update-sequence first annotate-event )
              gene-iri (-> evt-with-annotation ::ann/subjects :gene-iris first)
              disease-iri (-> evt-with-annotation ::ann/subjects :disease-iris first)
              first-genes-query-response (query genes-with-validity-query {})
              _ (event/process-event! (-> events :gene-validity-update-sequence first))
              second-genes-query-response (query genes-with-validity-query {})
              first-single-gene-query-response (query gene-query {:iri gene-iri})
              _ (event/process-event! (-> events :gene-validity-update-sequence second))
              second-single-gene-query-response (query gene-query {:iri gene-iri})]
          (is (not= first-genes-query-response second-genes-query-response))
          (is (not= first-single-gene-query-response second-single-gene-query-response)))))))
