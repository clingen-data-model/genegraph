(ns genegraph.transform.jsonld.common
  (:require [genegraph.database.names :refer [local-property-names
                                              property-uri->keyword]]
            [genegraph.util :refer [str->bytestream]]
            [io.pedestal.log :as log]
            [cheshire.core :as json]
            [genegraph.database.load :as l])
  (:import (genegraph.database.query.types RDFResource)
           (org.apache.jena.rdf.model Model)
           (org.apache.jena.riot RDFFormat RDFDataMgr Lang JsonLDWriteContext RDFWriter)
           (com.apicatalog.jsonld.document JsonDocument)
           (com.apicatalog.jsonld JsonLd)
           (java.io StringWriter)))


(defn ^com.apicatalog.jsonld.document.JsonDocument string->JsonDocument
  "Converts a JSON string to a titanium-json-ld JsonDocument"
  [^String input-str]
  (-> input-str
      (str->bytestream)
      (JsonDocument/of)))

(defn add-properties-to-context
  "Takes a list of pairs of desired compacted name, and the genegraph property name it corresponds to.
  e.g. [[\"object\" :sepio/has-object] ...]

  Returns a map appropriate for inclusion in a JSON-LD @context for use in compacting properties."
  ([names-and-properties] (add-properties-to-context {} names-and-properties))
  ([context names-and-properties]
   (merge context
          (into {}
                (for [[k p] names-and-properties]
                  (do [k {"@id" (str (get local-property-names p))
                          "@type" "@id"}]))))))

(defn contextualizable-properties-in-model [model]
  (let [statements (iterator-seq (.listStatements model))
        predicates (map #(.getPredicate %) statements)
        gg-property-keywords (set (map property-uri->keyword predicates))]
    gg-property-keywords))

(defn ^String jsonld-compact
  [^String input-str ^String context-str]
  (log/debug :fn :jsonld-compact :input-str input-str :context-str context-str)
  (let [titanium-doc (string->JsonDocument input-str)
        titanium-context (string->JsonDocument context-str)
        ; Returns jakarta.json.JsonArray, which has a .getJsonObject(int index) method.
        ;expanded (string->JsonDocument
        ;           (.toString (.get (JsonLd/expand titanium-doc))))
        compacting (JsonLd/compact titanium-doc titanium-context)]
    ; expanding and setting compactArrays=false is too broad, turning too many
    ; things into arrays. We may just want specific things to be arrays.
    ;(.compactArrays compacting false)
    ; .get returns jakarta.json.JsonObject
    (-> compacting .get .toString)))

(defn ^String jsonld-to-jsonld-framed
  "Takes a JSON-LD (1.0 or 1.1) string and a framing string. Returns a JSON-LD 1.1 string of the
  original object, with the frame applied."
  [^String input-str ^String frame-str]
  (log/debug :fn :to-jsonld-1-1-framed :input-str input-str :frame-str frame-str)
  (let [titanium-doc (string->JsonDocument input-str)
        titanium-frame (string->JsonDocument frame-str)
        framing (JsonLd/frame titanium-doc titanium-frame)]
    (let [;; This FramingApi/get call is expensive
          json-object (-> framing .get)
          out-str (.toString json-object)]
      out-str)))

(defn model-to-jsonld [^Model model]
  (log/debug :fn :model-to-jsonld)
  (.toString
   (doto (StringWriter.)
     (RDFDataMgr/write model RDFFormat/JSONLD_COMPACT_PRETTY))))


(comment
  ;; RDFDataMgr does not expose JSON-LD processing options, and these options are
  ;; not yet implemented for the JSON-LD 1.1 writer in Jena
  (defn model-to-jsonld [^Model model]
    (.toString
     (doto (StringWriter.)
       (RDFDataMgr/write model RDFFormat/JSONLD_PRETTY))))

  ;; There is a frame field on the Jena Context object, but it is not used
  ;; in the implementation of JsonLD11Writer, which is the class used by RDFWriter (an RDFDataMgr)

  ;; The below is an idea of how this would be implemented with these APIs.
  ;; For the moment, jsonld-to-jsonld-framed will continue to be used instead.
  (defn frame-jsonld-4-5 [^String input ^String frame-str]
    (log/info :fn :frame-jsonld-4-5 :input input :frame-str frame-str)
    (let [model ^Model (l/read-rdf (str->bytestream input) {:format :json-ld})
          write-ctx (JsonLDWriteContext.)]
      (.setFrame write-ctx frame-str)
      (let [rdf-writer (-> (doto (RDFWriter/create)
                           ;; TODO JSONLD11_FRAME_PRETTY
                             (.format RDFFormat/JSONLD10_FRAME_PRETTY)
                             (.source model)
                             (.context write-ctx))
                           .build)]
        (let [sw (StringWriter.)]
          (.output rdf-writer sw)
          (.toString sw))))))
