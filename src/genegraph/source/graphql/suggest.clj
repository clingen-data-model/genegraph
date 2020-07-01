(ns genegraph.source.graphql.suggest
  (:require [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.suggest.suggesters :refer [lookup]]
            [genegraph.suggest.serder :as ser]
            [clojure.string :as str])
  (:import [org.apache.lucene.search.suggest Lookup$LookupResult]
           [org.apache.lucene.util BytesRef]))

(defresolver suggest [args value]
  (let [text (:text args)
        contexts (if (some #{:ALL} (:contexts args))
                   ()
                   (:contexts args))
        count (:count args)
        key (keyword (str/lower-case (name (:suggest args))))]
    (into [] (lookup key text contexts count))))

(defn payload [value]
  (-> (.payload value)
      .bytes
      ser/deserialize))

(defresolver suggest-type [args value]
  (:type (payload value)))

(defresolver iri [args value]
  (:iri (payload value)))

(defresolver curie [args value]
  (:curie (payload value)))

(defresolver text [args value]
  (.key value))

(defresolver highlighted-text [args value]
  (.highlightKey value))

(defresolver weight [args value]
  (.value value))

(defresolver curations [args value]
  (:curations (payload value)))
