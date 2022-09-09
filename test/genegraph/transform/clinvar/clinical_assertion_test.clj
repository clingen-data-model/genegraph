(ns genegraph.transform.clinvar.clinical-assertion-test
  (:require [genegraph.transform.clinvar.clinical-assertion :as sut]
            [cheshire.core :as json]
            [genegraph.sink.event :as event]
            [genegraph.sink.document-store :as document-store]
            [genegraph.annotate :as ann]
            [genegraph.rocksdb :as rocks]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.test :refer :all]))

(def some-assertions (-> "test_events/clinvar.edn" io/resource slurp edn/read-string))

(defonce testdb (atom (rocks/open "document_store_test")))

(def scvs
  (->> some-assertions
              (map #(-> (assoc %
                               ::document-store/db @testdb
                               ::event/interceptors
                               [ann/add-metadata-interceptor
                                ann/add-data-interceptor
                                document-store/store-document-interceptor]
                               #_[document-store/add-data-interceptor
                                  document-store/add-id-interceptor
                                  document-store/add-is-storeable-interceptor
                                  document-store/store-document-interceptor])
                        event/process-event!))
              (filter #(= "VariationGermlineConditionStatement" (get-in % [::ann/data :type])))
              (map #(assoc % ::json (json/generate-string (::ann/data %) {:pretty true})))))

scvs

(doseq [scv scvs]
  (spit (str "/users/tristan/Desktop/scvs/"
             (get-in scv [::ann/data :id])
             ".json")
        (::json scv)))

(document-store/get-document @testdb "trait_939" )

#_(-> some-assertions
    second
    ::event/value
    (json/parse-string true)
    clojure.pprint/pprint)

