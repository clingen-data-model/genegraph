(ns clingen-search.database.datafy
  (:require [clojure.core.protocols :as p]
            [clojure.datafy :as d]
            [clingen-search.database.names :as names :refer [property-uri->keyword class-uri->keyword]]
            [clingen-search.database.instance :refer [db]]
            [clingen-search.database.util :refer [tx]]
            [mount.core :refer [defstate]]
            [clojure.set :as set]
            [clingen-search.database.query :as q])
  (:import [org.apache.jena.rdf.model Property Literal Resource ResourceFactory
            Statement]))



(declare datafy-resource)

(defn- compose-object [o]
  (cond (instance? Literal o) (.toString o)
        (instance? Resource o) 
        (with-meta (-> o .toString symbol)
          {::d/obj o
           ::d/class (class o)
           `p/datafy #(-> % meta ::d/obj datafy-resource)})))

;; TODO compose non-class resource string into namespaced keyword
;; TODO construct multiple property targets as collection
(defn datafy-resource [this]
  (tx 
   (let [model (.getUnionModel db)
         out-attributes (-> model (.listStatements this nil nil) iterator-seq)
         in-attributes (-> model (.listStatements nil nil this) iterator-seq)]
     {:> (into {} (map 
                     #(vector (-> % .getPredicate property-uri->keyword)
                              (-> % .getObject compose-object))
                     out-attributes))
      :<  (into {} (map 
                     #(vector (-> % .getPredicate property-uri->keyword)
                              (-> % .getSubject compose-object))
                     in-attributes))})))



(extend-protocol p/Datafiable
  
  Resource
  (datafy [this] (datafy-resource this))
  
  Property
  (datafy [this] (property-uri->keyword this))
  
  Literal
  (datafy [this] (.getString this)))
