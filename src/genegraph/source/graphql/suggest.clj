(ns genegraph.source.graphql.suggest
  (:require [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.suggest.suggesters :refer [lookup]]
            [taoensso.nippy :as nippy :refer [thaw]]
            [clojure.string :as str])
  (:import [org.apache.lucene.search.suggest Lookup$LookupResult]
           [org.apache.lucene.util BytesRef]))

(defn suggest [lacinia-context args value]
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
      thaw))

(defn suggest-type [context args value]
  (:type (payload value)))

(defn iri [context args value]
  (:iri (payload value)))

(defn curie [context args value]
  (:curie (payload value)))

(defn alternative-curie [context args value]
  (:alternative-curie (payload value)))

(defn text [context args value]
  (.key value))

(defn highlighted-text [context args value]
  (.highlightKey value))

(defn weight [context args value]
  (.value value))

(defn curations [context args value]
  (:curations (payload value)))
