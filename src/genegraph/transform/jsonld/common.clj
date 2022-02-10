(ns genegraph.transform.jsonld.common
  (:require [genegraph.database.names :refer [local-property-names
                                              property-uri->keyword]]
            [io.pedestal.log :as log]
            [cheshire.core :as json])
  (:import (genegraph.database.query.types RDFResource)
           (java.io StringWriter ByteArrayInputStream)
           (java.nio.charset Charset)
           (org.apache.jena.rdf.model Model)
           (org.apache.jena.riot.writer JsonLDWriter)
           (org.apache.jena.sparql.util Context)
           (org.apache.jena.sparql.core.mem DatasetGraphInMemory)
           (org.apache.jena.riot RDFFormat)
           (org.apache.jena.graph NodeFactory)
           (org.apache.jena.riot.system PrefixMapStd)
           (com.github.jsonldjava.core JsonLdOptions)
           (com.apicatalog.jsonld.document JsonDocument)
           (com.apicatalog.jsonld JsonLd)))


(defn ^com.apicatalog.jsonld.document.JsonDocument string->JsonDocument
  "Converts a JSON string to a titanium-json-ld JsonDocument"
  [^String input-str]
  (-> input-str
      (.getBytes (Charset/forName "UTF-8"))
      (ByteArrayInputStream.)
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
        compacting (JsonLd/compact titanium-doc titanium-context)]
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
    (-> framing .get .toString)))

(defn ^String model-to-jsonld
  "Takes a Jena Model object and a JSON-LD Framing 1.1 map.
  Returns a string of the model converted to JSON-LD 1.1, framed with the frame map."
  ;([^Model model]
  ; (model-to-jsonld model nil))
  ([^Model model]
   (let [writer (JsonLDWriter. RDFFormat/JSONLD_COMPACT_PRETTY)
         sw (StringWriter.)
         ds (DatasetGraphInMemory.)
         ; prefix-map left blank
         prefix-map (PrefixMapStd.)
         base-uri ""
         context (Context.)
         jsonld-options (JsonLdOptions.)]
     (.setUseNativeTypes jsonld-options true)
     (log/trace :msg "Adding model to dataset")
     ; we don't use the graph name on export, its value shouldn't appear in output
     (.addGraph ds (NodeFactory/createURI "BLANK") (.getGraph model))
     (log/trace :msg "Setting jsonld frame")
     ; TODO this frame option appears to do nothing when writing jsonld
     ; maybe it affects reading, not sure why it's under JsonLDWriter then though
     ;(.set context JsonLDWriter/JSONLD_FRAME frame-str)
     (log/trace :msg "Setting jsonld options")
     ; TODO does nothing
     ;(.setOmitGraph jsonld-options true)
     ; TODO does nothing
     ;(.setProcessingMode jsonld-options "JSON_LD_1_1")
     (.set context JsonLDWriter/JSONLD_OPTIONS jsonld-options)
     (log/trace :msg "Writing framed jsonld")
     (.write writer sw ds prefix-map base-uri context)
     (.toString sw))))
