(ns genegraph.database.property-store
  "RocksDB based store for literal properties in Jena.
  Designed to mitigate the costs of retrieving literals
  using the lookup methods available to Jena (outside of SPARQL)."
  (:require [genegraph.database.names :as names]
            [genegraph.rocksdb :as rocksdb]
            [mount.core :as mount :refer [defstate]]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [org.apache.jena.rdf.model Model Statement]))

(def data-properties (->> "data-properties.edn"
                          io/resource
                          slurp
                          edn/read-string
                          (map names/local-property-names)
                          set))

(def rocks-db-name "property_store")

(defstate property-store
  :start (rocksdb/open rocks-db-name)
  :stop (rocksdb/close property-store))

(defn get-property
  "Get the list of values for the given property on the given resource."
  [resource property]
  ;; Contract for ld-> functions expects a seq as a return value
  ;; may make more sense to move this there.
  (let [result (rocksdb/rocks-get-multipart-key
                property-store
                [(str resource) (str property)])]
    (if-not (= ::rocksdb/miss result)
      (vector result)
      [])))

(defn property-in-store?
  "Return true if the given property is among those included in the
  property store"
  [property]
  (data-properties property))

(defn put-property!
  "Associate the resource and property with the value in the store."
  [resource property value]
  (rocksdb/rocks-put-multipart-key! property-store
                                    [(str resource) (str property)]
                                    value))

(defn- statements-with-storable-properties [statements]
  (filter (fn [stmt] (and (data-properties (.getPredicate stmt))
                          (-> stmt .getObject .isLiteral)))
          statements))

(defn put-model!
  "Store the properties in the model that are flagged for inclusion."
  [model]
  (let [statements (->> (.listStatements model)
                        iterator-seq
                        statements-with-storable-properties)]
    (doseq [stmt statements]
      (put-property! (.getSubject stmt)
                     (.getPredicate stmt)
                     (-> stmt .getObject .asLiteral .getValue)))))
