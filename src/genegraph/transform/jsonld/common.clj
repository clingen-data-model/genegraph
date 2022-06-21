;; Does not work as advertised in current JENA
;; Removing from dep tree until resolved =tristan
(ns genegraph.transform.jsonld.common
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [genegraph.database.load :as l]
            [genegraph.database.names :refer [local-property-names property-uri->keyword]]
            [io.pedestal.log :as log])
  (:import [com.apicatalog.jsonld JsonLd]
           [com.apicatalog.jsonld.document JsonDocument]
           [com.github.jsonldjava.core JsonLdOptions]
           [genegraph.database.query.types RDFResource]
           [java.io StringWriter]
           [org.apache.jena.graph NodeFactory]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.riot RDFFormat RDFDataMgr Lang JsonLDWriteContext RDFWriter]
           [org.apache.jena.riot.system PrefixMapStd]
           [org.apache.jena.riot.writer JsonLD11Writer]
           [org.apache.jena.sparql.core.mem DatasetGraphInMemory]
           [org.apache.jena.sparql.util Context]))

(defn ^com.apicatalog.jsonld.document.JsonDocument string->JsonDocument
  "Converts a JSON string to a titanium-json-ld JsonDocument"
  [^String input-str]
  (-> input-str
      (->> (map byte) byte-array io/input-stream)
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
  (log/trace :fn :jsonld-compact :input-str input-str :context-str context-str)
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
  (log/trace :fn :to-jsonld-1-1-framed :input-str input-str :frame-str frame-str)
  (let [titanium-doc (string->JsonDocument input-str)
        titanium-frame (string->JsonDocument frame-str)
        framing (JsonLd/frame titanium-doc titanium-frame)]
    (-> framing .get .toString)))

(comment
  '(defn ^String model-to-jsonld
     "Takes a Jena Model object and a JSON-LD Framing 1.1 map.
  Returns a string of the model converted to JSON-LD 1.1, framed with the frame map."
     ([^Model model]
      (model-to-jsonld model nil))
     ([^Model model ^String frame-str]
      ;; reactivate when JSON-LD support is up-to-date with Jena 4.5
      (comment
        (let [writer (JsonLDWriter. RDFFormat/JSONLD_COMPACT_PRETTY)
              sw (StringWriter.)
              ds (DatasetGraphInMemory.)
                                        ; prefix-map left blank
              prefix-map (PrefixMapStd.)
              base-uri ""
              context (Context.)
              jsonld-options (JsonLdOptions.)]
          (.setUseNativeTypes jsonld-options true)
                                        ;(.setCompactArrays jsonld-options false)
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
          (.toString sw))))))

(defn model-to-jsonld
  "Return a string with the JSON-LD representation of MODEL."
  [^Model model]
  (.toString
   (doto (StringWriter.)
     (RDFDataMgr/write model RDFFormat/JSONLD_COMPACT_PRETTY))))

(comment
  ;; There is a frame field on the Jena Context object, but it is not used
  ;; in the implementation of JsonLD11Writer, which is the class used by RDFWriter (an RDFDataMgr)

  (defn frame-jsonld-4-5 [^String input ^String frame-str]
    (log/info :fn :frame-jsonld-4-5 :input input :frame-str frame-str)
    (let [model ^Model (-> input
                           (->> (map byte) byte-array io/input-stream)
                           (l/read-rdf {:format :json-ld}))
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
