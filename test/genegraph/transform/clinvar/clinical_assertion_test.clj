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

(->> some-assertions
     (map #(-> (assoc %
                      ::document-store/db @testdb
                      ::event/interceptors
                      [ann/add-data-interceptor
                       document-store/store-document-interceptor]
                      #_[document-store/add-data-interceptor
                       document-store/add-id-interceptor
                       document-store/add-is-storeable-interceptor
                       document-store/store-document-interceptor])
               event/process-event!
               ))
     last)

(document-store/get-document @testdb "trait_939" )

#_(-> some-assertions
    second
    ::event/value
    (json/parse-string true)
    clojure.pprint/pprint)

