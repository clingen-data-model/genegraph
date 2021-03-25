(ns genegraph.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as server]
            [genegraph.service :as service]
            [cheshire.core :as json]
            [com.walmartlabs.lacinia.schema :as schema]
            [io.pedestal.test :refer [response-for]])
  (:import java.time.Instant))

(defn create-query-fn [server]
  (fn [request variables]
    (let [response (response-for server
                                 :post "/graphql"
                                 :headers {"Content-Type" "application/json"}
                                 :body (json/generate-string {:query request :variables variables}))]
      (assoc response :json (json/parse-string (:body response) true)))))

(deftest request-gating-test
  (let [schema (schema/compile
                {:queries 
                 {:hello
                  {:type 'String
                   :resolve (fn  [& _]
                              (let [t (Instant/now)]
                                (Thread/sleep 100)
                                (str t)))}}})
        server (::server/service-fn (server/create-servlet (service/service schema)))
        query (create-query-fn server)]
    (let [result (->> (repeatedly promise)
                      (take 5))]
      (doseq [r result]
        (.start (Thread. #(deliver r (query "{hello}" {})))))
      (testing "Request gate should have one unique result"
          (is (= 1 (->> result (map #(:body @%)) (into #{}) count)))))))
