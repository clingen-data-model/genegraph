(ns genegraph.database.query.types
  (:require [clojure.core.protocols :as protocols :refer [Datafiable]]
            [clojure.datafy :as datafy :refer [datafy]]
            [clojure.string :as s]
            [genegraph.database.instance :refer [db]]
            [genegraph.database.property-store :as property-store]
            [genegraph.database.names
             :as
             names
             :refer
             [class-uri->keyword local-names prefix-ns-map property-uri->keyword]]
            [genegraph.database.util :as util :refer [tx]]
            [taoensso.nippy :as nippy])
  (:import [org.apache.jena.rdf.model
            Literal
            RDFList
            Resource
            ResourceFactory
            AnonId
            Model
            ModelFactory]
           [java.io
            ByteArrayOutputStream
            ByteArrayInputStream]
           [org.apache.jena.riot RDFDataMgr Lang]
           [org.apache.jena.datatypes.xsd.impl
            XSDBaseNumericType]))

(def first-property (ResourceFactory/createProperty "http://www.w3.org/1999/02/22-rdf-syntax-ns#first"))

(defprotocol Steppable
  (step [edge start model]))

(defprotocol AsReference
  (to-ref [resource]))

(defprotocol AsClojureType
  (to-clj [x model]))

(defprotocol AsRDFNode
  (to-rdf-node [x]))

(defprotocol Addressable
  "Retrieves the local path to a resource"
  (path [this]))

(defprotocol ThreadableData
  "A data structure that can be accessed through the ld-> and ld1-> accessors
  similar to Clojure XML zippers"
  (ld-> [this ks])
  (ld1-> [this ks]))

(defprotocol RDFType
  (is-rdf-type? [this rdf-type]))

(defprotocol AsJenaResource
  (as-jena-resource [this]))

(defprotocol AsResource
  "Create an RDFResource given a reference"
  (resource [r] [ns-prefix r]))

(declare datafy-resource)

(declare navize)

(defn- compose-object-for-datafy [o]
  (cond (instance? Literal o) (.toString o)
        (instance? Resource o) 
        (with-meta (-> o .toString symbol)
          {::datafy/obj o
           ::datafy/class (class o)
           `protocols/datafy #(-> % meta ::datafy/obj datafy-resource)})))

;; REFACTOR Would prefer to restructure RDFResource to take a nil model reference
;; as a signal to use the default DB, rather than requiring each resource to include
;; explicit reference. This is a placeholder until this can be accomplished
(defn get-all-graphs []
  (or @util/current-union-model (.getUnionModel db)))



(deftype RDFResource [resource model local-bindings]

  AsJenaResource
  (as-jena-resource [_] resource)

  RDFType
  (is-rdf-type? [this rdf-type] 
    (let [t (if (= (type rdf-type) clojure.lang.Keyword) 
              (local-names rdf-type)
              (ResourceFactory/createResource rdf-type))]
      (tx (.contains model resource (local-names :rdf/type) t))))

  ;; TODO, returns all properties when k does not map to a known symbol,
  ;; This seems to break the contract for ILookup
  clojure.lang.ILookup
  (valAt [this k] (or (get local-bindings k) (step k this model)))
  (valAt [this k nf] nf) ;; TODO fix this


  ;; Conforms to the expectations for a sequence representation of a map. Includes only
  ;; properties where this resource is the subject. Sequence is fully realized
  ;; in order to permit access outside transaction
  clojure.lang.Seqable
  (seq [this]
    (tx
     (let [out-attributes (-> model (.listStatements resource nil nil) iterator-seq)]
       (doall (map #(vector 
                     (-> % .getPredicate property-uri->keyword)
                     (to-clj (.getObject %) model)) out-attributes)))))

  Object
  (toString [_] (.toString resource))
  (equals [this other]
    (and (satisfies? AsJenaResource other)
         (= resource (as-jena-resource other))))
  (hashCode [_] (.hashCode resource))
  

  AsReference
  (to-ref [_] (class-uri->keyword resource))

  Datafiable
  (datafy [_] 
    (tx 
     (let [out-attributes (-> model (.listStatements resource nil nil) iterator-seq)
           in-attributes (-> model (.listStatements nil nil resource) iterator-seq)]

       (with-meta
         (into [] (concat
                   (mapv #(with-meta [[(-> % .getPredicate property-uri->keyword) :>]
                                      (-> % .getObject compose-object-for-datafy)]
                            {:genegraph.database.query/value  (.getObject %)})
                         out-attributes)
                   (mapv #(with-meta [[(-> % .getPredicate property-uri->keyword) :<]
                                      (-> % .getSubject compose-object-for-datafy)]
                            {:genegraph.database.query/value (.getSubject %)})
                         in-attributes)))
         {`protocols/nav (navize model)}))))

  ;; TODO Flattening the ld-> has potentially undesirable behavior with RDFList, consider
  ;; how flatten is being used in this context
  ThreadableData
  (ld-> [this ks] (reduce (fn [nodes k]
                            (->> nodes
                                 (filter #(satisfies? ThreadableData %))
                                 (map #(step k % model))
                                 (filter seq) flatten))
                          [this] 
                          ks))
  (ld1-> [this ks] (first (ld-> this ks)))

  Addressable
  (path [_] (let [uri (.getURI resource)
                  short-ns (names/curie uri)
                  full-ns (prefix-ns-map short-ns)
                  id (subs uri (count full-ns))]
              (str "/r/" short-ns "_" id))))

(defn create-resource
  "Create an RDF resource, optionally bound to model, or containing stored values (generally extra variables projected from a query result)."
  ([jena-resource] (create-resource jena-resource (get-all-graphs) {}))
  ([jena-resource model] (create-resource jena-resource model {}))
  ([jena-resource model local-bindings] (->RDFResource jena-resource model local-bindings)))

(defn- navize [model]
  (fn [coll k v]
    (let [target (:genegraph.database.query/value (meta v))]
      (if (instance? Resource target)
        (create-resource target model)
        target))))

(extend-protocol AsResource
  
  java.lang.String
  (resource 
    ([r] (let [[_ curie-prefix rest] (re-find #"^([a-zA-Z]+)[:_](.*)$" r)]
           (if curie-prefix
             (create-resource 
              (ResourceFactory/createResource             
               (if-let [iri-prefix (-> curie-prefix s/lower-case prefix-ns-map)]
                 (str iri-prefix rest)
                 r)))
             (create-resource (ResourceFactory/createResource r)))))
    ([ns-prefix r] (when-let [prefix (prefix-ns-map ns-prefix)]
                     (create-resource (ResourceFactory/createResource (str prefix r))))))
  
  clojure.lang.Keyword
  (resource [r] (when-let [res (local-names r)]
                  (create-resource res)))

  org.apache.jena.rdf.model.Resource
  (resource [r] (create-resource r)))

(nippy/extend-freeze 
 RDFResource ::rdf-resource
 [x data-output]
 (let [resource-descriptor
       (if-let [id (-> x as-jena-resource .getURI)]
         {:iri id}
         {:bnode-id (-> x as-jena-resource .getId str)})]
   (nippy/freeze-to-out! data-output resource-descriptor)))

(nippy/extend-thaw 
 ::rdf-resource
 [data-input]
 (let [resource-descriptor (nippy/thaw-from-in! data-input)]
   (if (:iri resource-descriptor)
     (resource (:iri resource-descriptor))
     (resource (.createResource
                (get-all-graphs)
                (-> resource-descriptor :bnode-id AnonId/create))))))

;; Jena read methods expect to read to the end of the stream
;; when offered one as input. Since Nippy presents the
;; model serialized with other objects in the stream, need to
;; extract the serialized model from the stream before passing
;; to Jena. I suppose this costs us some unnecessary memcpy.
;; An alternative might be extending an input-stream class
;; that can send EOF after a fixed number of bytes are read.
;; Doesen't seem worth the work (right now)

(nippy/extend-freeze
 Model ::model
 [x data-output]
 (let [model-stream (ByteArrayOutputStream.)
       _ (RDFDataMgr/write model-stream x Lang/RDFTHRIFT)
       model-bytes (.toByteArray model-stream)]
   (.writeLong data-output (alength model-bytes)) ; add byte count as prefix
   (.write data-output model-bytes 0 (alength model-bytes))))

(nippy/extend-thaw
 ::model
 [data-input]
 (let [model-byte-count (.readLong data-input)
       model-bytes (make-array Byte/TYPE model-byte-count)
       model (ModelFactory/createDefaultModel)]
   (.read data-input model-bytes 0 model-byte-count)
   (RDFDataMgr/read model (ByteArrayInputStream. model-bytes) Lang/RDFTHRIFT)
   model))

(defn- kw-to-property [kw]
  (if-let [prop (names/local-property-names kw)]
    prop
    (when-let [ns (-> kw namespace prefix-ns-map)]
      (ResourceFactory/createProperty (str ns (name kw))))))

(extend-protocol Steppable

  ;; Single keyword, treat as [:ns/prop :>] (outward pointing edge)
  clojure.lang.Keyword
  (step [edge start model]
    (step [edge :>] start model))
  
  ;; Expect edge to be a vector with form [:ns/prop <direction>], where direction is one
  ;; of :> :< :-
  ;; TODO fail more gracefully when starting point is a literal
  clojure.lang.IPersistentVector
  (step [edge start model]
    (let [property (kw-to-property (first edge))]
      (if false ;; (property-store/property-in-store? property)
        (property-store/get-property start property)
        (tx 
         (let [out-fn (fn [n] (->> (.listObjectsOfProperty model (.resource n) property)
                                   iterator-seq))
               in-fn (fn [n] (->> (.listResourcesWithProperty model property (.resource n))
                                  iterator-seq))
               both-fn #(concat (out-fn %) (in-fn %))
               step-fn (case (second edge)
                         :> out-fn
                         :< in-fn
                         :- both-fn)
               result (mapv #(to-clj % model) (step-fn start))]
           (case (count result)
             0 nil
             ;; 1 (first result)
             result)))))))

(defn resource?
  "Return true if the object is an RDFResource"
  [r]
  (satisfies? AsJenaResource r))

(extend-protocol AsRDFNode

  java.lang.String
  (to-rdf-node [x] (ResourceFactory/createPlainLiteral x))

  ; Accept integers as node literals. RDF still stores it as a string, but just with a type metadata
  java.lang.Integer
  (to-rdf-node [x] (ResourceFactory/createTypedLiteral (str x) XSDBaseNumericType/XSDinteger))

  clojure.lang.Keyword
  (to-rdf-node [x] (local-names x))
  
  RDFResource
  (to-rdf-node [x] (as-jena-resource x)))

(defn- rdf-list-to-vector [rdf-list-node model]
  (let [rdf-list (.as rdf-list-node RDFList)]
    (->> rdf-list .iterator iterator-seq (mapv #(to-clj % model)))))

(extend-protocol AsClojureType

  Resource
  (to-clj [x model] (if (.hasProperty x first-property)
                      (rdf-list-to-vector x model)
                      (create-resource x model)))
  
  Literal
  (to-clj [x model] (.getValue x)))
