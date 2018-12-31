(ns clingen-search.database.datafy
  (:require [clojure.core.protocols :as p]
            [clojure.datafy :as d]
            [clingen-search.database.names :as names]
            [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [tx]]
            [mount.core :refer [defstate]]
            [clojure.set :as set])
  (:import [org.apache.jena.rdf.model Property Literal Resource ResourceFactory
            Statement]))

(defstate property-uri->keyword
  :start (set/map-invert names/local-property-names))

(defstate class-uri->keyword
  :start (set/map-invert names/local-class-names))

;; TODO compose non-class resource string into namespaced keyword
;; TODO construct multiple property targets as collection
(defn datafy-resource [this]
  (tx 
   (let [model (.getDefaultModel db)
         out-attributes (-> model (.listStatements this nil nil) iterator-seq)
         in-attributes (-> model (.listStatements nil nil this) iterator-seq)]
     {:out (into {} (map 
                     #(vector (-> % .getPredicate property-uri->keyword)
                              (-> % .getObject compose-object))
                     out-attributes))
      :in  (into {} (map 
                     #(vector (-> % .getPredicate property-uri->keyword)
                              (-> % .getSubject compose-object))
                     in-attributes))})))

(defn- compose-object [o]
  (cond (instance? Literal o) (.toString o)
        (instance? Resource o) 
        (with-meta (-> o .toString symbol)
          {::d/obj o
           ::d/class (class o)
           `p/datafy #(-> % meta ::d/obj datafy-resource)})))

(extend-protocol p/Datafiable

  Resource
  (datafy [this] (datafy-resource this))
  
  Property
  (datafy [this] (property-uri->keyword this))
  
  Literal
  (datafy [this] (.getString this)))
