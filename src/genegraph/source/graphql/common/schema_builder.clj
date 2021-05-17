(ns genegraph.source.graphql.common.schema-builder
  (:require [clojure.edn :as edn]
            [camel-snake-kebab.core :as csk]))

(def default-build-options
  {:base-ns "genegraph.model"})

(defn- add-default-resolver-to-field [[field-name properties] ns]
  ;; base-ns is a string, field-name is a keyword, hence the awkward
  ;; construction
  [field-name
   (assoc properties
          :resolve
          (:resolve properties (symbol ns (name field-name))))])

(defn- add-default-resolvers-to-fields-in-object [[object-name properties] base-ns]
  (let [ns (str base-ns "." (name object-name))
        fields (->> (:fields properties)
                    (map #(add-default-resolver-to-field % ns))
                    (into {}))]
    [object-name (assoc properties :fields fields)]))

(defn add-default-resolvers-to-fields [schema object-type]
  (let [base-ns (get-in schema [::options :base-ns])
        objects (->> (get schema object-type)
                     (map #(add-default-resolvers-to-fields-in-object % base-ns))
                     (into {}))]
    (assoc schema object-type objects)))

(defn build
  ([schema-source] (build schema-source {}))
  ([schema-source options]
   (-> (slurp schema-source)
       edn/read-string
       (assoc ::options (merge options default-build-options))
       (add-default-resolvers-to-fields :interfaces))))
