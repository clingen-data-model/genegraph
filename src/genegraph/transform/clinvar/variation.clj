(ns genegraph.transform.clinvar.variation
  (:require [cheshire.core :as json]
            [clojure.stacktrace :as stacktrace]
            [clojure.walk :as walk]
            [genegraph.annotate.cnv :as cnv]
            [genegraph.database.names :as names :refer [local-class-names
                                                        local-property-names
                                                        prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.sink.document-store :as docstore]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.transform.clinvar.common :as common]
            [genegraph.transform.clinvar.iri :as iri :refer [ns-cg]]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.util :refer [coll-subtract]]
            [io.pedestal.log :as log]
            [mount.core :as mount :refer [defstate]]
            [taoensso.nippy :as nippy]
            [clojure.set :as set]))

(def clinvar-variation-type (ns-cg "ClinVarVariation"))
(def variation-frame
  "Frame map for variation"
  {"@type" "https://vrs.ga4gh.org/terms/CanonicalVariationDescriptor"})

(declare variation-context)

(defn add-contextualized [event]
  (let [data (:genegraph.annotate/data event)]
    (assoc event
           :genegraph.annotate/data-contextualized
           (merge data variation-context))))

(defn get-spdi-sequence-accession
  "Returns the sequence identifier for a SPDI expression.
   Example: NC_000012.12:120999688:CCCC:CCCCC -> NC_000012.12"
  [spdi]
  (-> (re-find #"^([a-zA-Z]+_[a-zA-Z0-9]+\.[a-zA-Z0-9]+):" spdi)
      (nth 1)))

(defn get-hgvs-sequence-accession
  [hgvs]
  (get-spdi-sequence-accession hgvs))

(defn prioritized-variation-expressions-all
  "Attempts to return a 'canonical' expression of the variation in ClinVar.
  Takes a clinvar-raw variation message. Returns one string expression.
  Tries GRCh38, GRCh37, may add additional options in the future.
  When no 'canonical' expressions found, falls back to Text VRS using variation.id.

  NOTE: .content.content must be parsed already."
  [variation-msg]
  (log/trace :fn :prioritized-variation-expression-all :variation-msg variation-msg)
  (let [nested-content (-> variation-msg :content :content)]
    (letfn [(get-hgvs [nested-content assembly-name]
              (let [hgvs-list (-> nested-content (get "HGVSlist") (get "HGVS") util/into-sequential-if-not)
                    filtered (filterv #(= assembly-name (get-in % ["NucleotideExpression" "@Assembly"])) hgvs-list)]
                ;; TODO add a check here to ensure this is just a duplicate expression
                ;; If the expressions are different, will need a deterministic way to select one.
                (when (< 1 (count filtered))
                  (log/warn :fn :prioritized-variation-expression
                            :msg (str "Multiple expressions for variation for assembly: " assembly-name)
                            :variation-id (-> variation-msg :content :id)
                            :filtered-list filtered))
                (-> filtered first (get "NucleotideExpression") (get "Expression") (get "$"))))
            (get-spdi [nested-content]
              (-> nested-content (get "CanonicalSPDI") (get "$")))
            (get-sequence-location [nested-content sequence-accession]
              (log/info :nested-content nested-content
                        :sequence-accession sequence-accession)
              (->> (get-in nested-content ["Location" "SequenceLocation"])
                   util/into-sequential-if-not
                   (filter #(= sequence-accession (get % "@Accession")))
                   first
                   (#(do (log/info :location %) %))))]
      (let [exprs (for [val-opt [{:fn #(get-spdi nested-content)
                                  :type :spdi
                                  :label "SPDI"}
                                 {:fn #(get-hgvs nested-content "GRCh38")
                                  :type :hgvs
                                  :label "GRCh38"}
                                 {:fn #(get-hgvs nested-content "GRCh37")
                                  :type :hgvs
                                  :label "GRCh37"}
                                 ;; Fallback to using the variation id
                                 {:fn #(str "clinvar:" (-> variation-msg :content :id))
                                  :type :text
                                  :label "Text"}]]
                    (let [expr ((get val-opt :fn))]
                      (when expr {:expr expr
                                  :type (:type val-opt)
                                  :label (:label val-opt)
                                  :location (let [seq-accession (case (:type val-opt)
                                                                  :spdi (get-spdi-sequence-accession expr)
                                                                  :hgvs (get-hgvs-sequence-accession expr)
                                                                  nil)]
                                              (when seq-accession
                                                (let [sequence-location (get-sequence-location nested-content
                                                                                               seq-accession)]
                                                  (merge {:start (get sequence-location "@start")
                                                          :stop (get sequence-location "@stop")}
                                                         (when-let [l (some-> (get sequence-location "@variantLength")
                                                                              parse-long)]
                                                           {:variant-length l})))))})))]
        ;; Evaluate all and return non-nil
        (into [] (filter #(not (nil? %)) exprs))))))


(defn hgvs-syntax-from-change
  "Takes a change string like g.119705C>T and returns a VRS syntax string like \"hgvs.g\""
  [^String change]
  (let [change-prefix-map {"c." "hgvs.c", "p." "hgvs.p", "g." "hgvs.g",
                           "m." "hgvs.m", "n." "hgvs.n", "r." "hgvs.r"}]
    (if change
      (or (some #(when (.startsWith change (key %)) (val %)) change-prefix-map)
          (log/debug :message "Unknown hgvs change syntax."
                     :change change))
      (log/debug :message "No change provided"))))

(defn hgvs-syntax-from-expression
  "This function attempts to determine the hgvs syntax from the expression.
   It uses regex and may be less reliable than using hgvs-syntax-from-change,
   which looks at the '[a-z]\\.' prefix on an HGVS change term if provided."
  [^String expression]
  (let [found (re-find #"[\w_]+:([cpgmnr])\..+" expression)]
    (if (empty? found)
      (log/warn :message "Could not determine hgvs syntax from expression"
                :expression expression)
      ;; re-find returns the first matched sequence followed by groups. We want first group.
      (when (and (< 1 (count found)) (nth found 1))
        (str "hgvs." (nth found 1))))))

(defn nucleotide-hgvs
  "Takes an object in the form of ClinVar's HGVSlist.
  Returns an {:expression :assembly :syntax} map if a nucleotide HGVS expression is present."
  [hgvs-obj]
  (when (get hgvs-obj "NucleotideExpression")
    {:expression (-> hgvs-obj (get "NucleotideExpression") (get "Expression") (get "$"))
     :assembly (-> hgvs-obj (get "NucleotideExpression") (get "Assembly"))
     :syntax (let [syntax (or (hgvs-syntax-from-change
                               (get-in hgvs-obj ["NucleotideExpression" "@change"]))
                              (hgvs-syntax-from-expression
                               (get-in hgvs-obj ["NucleotideExpression" "Expression" "$"])))]
               (when (nil? syntax) (log/error :fn :nucleotide-hgvs
                                              :msg "Could not determine hgvs syntax"
                                              :hgvs-obj hgvs-obj))
               syntax)}))

(defn protein-hgvs
  "Takes an object in the form of ClinVar's HGVSlist.
  Returns an {:expression :assembly :syntax} map if a protein HGVS expression is present."
  [hgvs-obj]
  (when (get hgvs-obj "ProteinExpression")
    {:expression (get-in hgvs-obj ["ProteinExpression" "Expression" "$"])
     :syntax (let [syntax (or (hgvs-syntax-from-change
                               (get-in hgvs-obj ["ProteinExpression" "@change"]))
                              (hgvs-syntax-from-expression
                               (get-in hgvs-obj ["ProteinExpression" "Expression" "$"])))]
               (when (nil? syntax) (log/error :fn :protein-hgvs
                                              :msg "Could not determine hgvs syntax"
                                              :hgvs-obj hgvs-obj))
               syntax)}))

(defn get-all-expressions
  "Attempts to return a 'canonical' expression of the variation in ClinVar.
  Takes a clinvar-raw variation message. Returns one string expression.
  Tries GRCh38, GRCh37, may add additional options in the future.

  Returns seq of maps of
  :expression
  :syntax
  :syntax-version (not currently implemented for any)

  Example of HGVSlist objects being parsed:
  ProteinExpression:
  {\"@Type\" \"protein\",
   \"ProteinExpression\"
      {\"@change\" \"p.Thr380Met\",
       \"@sequenceAccession\" \"P00439\",
       \"@sequenceAccessionVersion\" \"P00439\",
       \"Expression\"
          {\"$\" \"P00439:p.Thr380Met\"}}}

  NucleotideExpression is similar format.

  NOTE: .content.content should be parsed already, but it tries to parse it again if not."
  [variation-msg]
  (log/debug :fn ::get-all-expressions :variation-msg variation-msg)
  (let [nested-content (-> variation-msg :content :content)
        hgvs-list (-> nested-content (get "HGVSlist") (get "HGVS") util/into-sequential-if-not)]
    (let [expressions
          (filter
           #(not (nil? %))
           (concat
            (->> hgvs-list
                 (map (fn [hgvs-obj]
                        (log/trace :fn ::get-all-expressions :hgvs-obj hgvs-obj)
                        (let [outputs (filter not-empty [(nucleotide-hgvs hgvs-obj)
                                                         (protein-hgvs hgvs-obj)])]
                          (when (empty? outputs)
                            (let [e (ex-info "Found no HGVS expressions" {:hgvs-obj hgvs-obj :variation-msg variation-msg})]
                              (log/warn :message (ex-message e) :data (ex-data e))))
                          outputs)))
                 (apply concat))
            [(let [spdi-expr (-> nested-content (get "CanonicalSPDI") (get "$"))]
               (when spdi-expr
                 {:expression spdi-expr
                  :syntax "spdi"}))]))]
      expressions)))

;; TODO if variantLength is not defined, treat as if it was greater than 50
(defn variation-preprocess
  "Performs some annotations to the variation event in order to enable downstream transformation.

   Parses expressions and determines their types (spdi, hgvs, clinvar copy number).
   Adds locally namespaced keywords to the message.

   If a variation event is annotated with ::copy-number?, add information about what it is.

   For CNV with explicit observed counts, parse these absolute copy parameters.

   For CNV without these counts,
   - if the region duplicaton/deletion spans at least 50 base pairs, treat as
     a relative gain/loss
   - if the region duplication/deletion is less than 50 bp, treat as non-CNV

   For CNV:
   - adds ::cnv {:copy-number-type <:absolute or :relative>
                 ... if relative :hgvs <deldup-expression-string>}
   - adds ::prioritized-expression {:type :cnv
                                    :expr <same contents as ::cnv>
                                    :label \"CNV\"}
   - downstream fns should just use ::prioritized-expression

   For non-CNV:
   - adds ::prioritized-expression {:type <:spdi or :hgvs or :text>
                                    :expr <string>}
   - adds ::canonical-candidate-expressions
            [<same structure as prioritized-expression> ...]
   "
  [event]
  (let [value (:genegraph.transform.clinvar.core/parsed-value event)
        prioritized-expressions (prioritized-variation-expressions-all value)
        variation-type (-> value :content :variation_type)
        deldup-cnv-threshold 50]
    (letfn [(-try-absolute-copy-number
              [event]
              "Adds ::cnv if it looks like an absolute copy number variant"
              (if (and (not (::cnv event))
                       (.startsWith variation-type "copy number"))
                (-> (assoc event ::copy-number? true)
                    (#(if-let [absolute-cnv (cnv/parse (-> value :content :name))]
                        (assoc % ::cnv (assoc absolute-cnv :copy-number-type :absolute))
                        %)))
                event))
            (-try-relative-copy-number
              [event]
              "Adds ::cnv if it looks like a relative copy number variant.
               Must be called *after* try-absolute-copy-number"
              (let [hgvs-exprs (->> prioritized-expressions
                                    (filter #(= :hgvs (:type %))))]
                (log/info :variation-type variation-type :hgvs-exprs hgvs-exprs)
                (if (and (not-empty hgvs-exprs)
                         (or (and (.startsWith variation-type "copy number")
                                  (not (::cnv event)))
                             (and (#{"Duplication" "Deletion"} variation-type)
                                  (->> hgvs-exprs
                                       (map #(or (get-in % [:location :variant-length]) 0))
                                       (filter #(<= deldup-cnv-threshold %))
                                       not-empty))))
                ;; looks like a relative cnv
                  (let [hgvs-expr-obj (first hgvs-exprs)
                        hgvs-str (:expr hgvs-expr-obj)
                        copy-class (cond
                                     (#{"Duplication" "Deletion"} variation-type) variation-type
                                     (.contains hgvs-str "dup") "Duplication"
                                     (.contains hgvs-str "del") "Deletion"
                                     :else (do (log/error :msg "Unknown hgvs copy class"
                                                          :hgvs-expr-obj hgvs-expr-obj
                                                          :variation value)
                                               nil))]
                    (assoc event ::cnv {:copy-number-type :relative
                                        :hgvs (assoc hgvs-expr-obj :copy-class copy-class)}))
                  event)))

            (try-copy-number
              [event]
              (-> event
                  (-try-absolute-copy-number)
                  (-try-relative-copy-number)))]
      (comment
        "- Absolute Copy Number
         - Relative Copy Number based on being 'variation_type~=copy number' and being a del/dup of least 50bp
         Have to call these in order. First try absolute, then relative, then all other")
      (-> event
          (try-copy-number)

          (#(if (::cnv %)
              (assoc %
                     ::copy-number? true
                     ::prioritized-expression {:expr (::cnv %)
                                               :type :cnv})
              %))

          (#(if-not (::cnv %)
              (assoc %
                     ::canonical-candidate-expressions prioritized-expressions
                     ::prioritized-expression (first prioritized-expressions))
              %))

          ;; Add all the other expressions
          ;; (GRCh38 SPDI, HGVS top-level GRCh38, HGVS top-level GRCh37, fallback clinvar:id)
          (assoc ::other-expressions (get-all-expressions value))))))

(defn make-index-replacer
  "Returns a function which when called with a value,
   replaces the INDEX of INPUT-VECTOR with that value."
  [input-vector index]
  (fn [value] (assoc input-vector index value)))

(defn recursive-replace-keys
  "Recursively replace keys in input-map and its values, for keys matching
   key-match-fn, by applying key-mutate-fn"
  [input-map key-match-fn key-mutate-fn]
  (letfn [(apply-to-key [k]
            (if (key-match-fn k) (key-mutate-fn k) k))
          (apply-to-value [v]
            (cond (map? v) (recursive-replace-keys v key-match-fn key-mutate-fn)
                  (sequential? v) (map #(apply-to-value %) v)
                  :else v))]
    (into {} (map (fn [[k v]]
                    (vector (apply-to-key k)
                            (apply-to-value v)))
                  input-map))))

(defn make-member-map
  "Creates a set of VariationMember triples based on an expression and syntax.
  Returns a seq of seqs"
  [node-iri expression syntax syntax-version]
  (log/trace :fn :make-member :node-iri node-iri
             :expression expression
             :syntax syntax
             :syntax-version syntax-version)
  ;;member-iri (l/blank-node)
  ;;expression-iri (l/blank-node)
  {;;:id member-iri
   :type "VariationMember"
   :expressions [(merge
                  {;;expression-iri
                   :type "Expression"
                   :syntax syntax
                   :value expression}
                  (when syntax-version
                    {:syntax_version syntax-version}))]})

(defn get-vrs-variation-map
  "Takes a map with :expression (String or Map) and :expression-type (Keyword).

   Returns a map containing :iri (String) and :variation (map)"
  [{expression :expression
    expression-type :expression-type :as inp}]
  (log/debug :fn :get-vrs-variation-map :inp inp)
  (let [vrs-obj (vicc/vrs-variation-for-expression
                 (-> expression)
                 (-> expression-type keyword))]
    (log/debug :fn :get-vrs-variation-map :vrs-obj vrs-obj)
    (if (empty? vrs-obj)
      (let [e (ex-info "No variation received from VRS normalization" {:fn :add-vrs-model :expression expression})]
        (log/error :message (ex-message e) :data (ex-data e))
        (throw e))
      (let [vrs-obj-pretty (-> vrs-obj
                               (recursive-replace-keys
                                (fn [k] (= "_id" k))
                                (fn [k] "id"))
                               (walk/keywordize-keys))
            vrs-id (:id vrs-obj-pretty)]
        (log/debug :fn :add-vrs-model :vrs-id vrs-id :vrs-obj vrs-obj-pretty)
        {:iri vrs-id :variation vrs-obj-pretty}))))


(defn normalize-canonical-expression
  "Selects the canonical expression and returns it and the normalized form
   {:expression ... :normalized ...}"
  [event]
  (comment
    "Candidate expressions are sorted in preferred order and are all non-cnv maps like:"
    {:expr "...." :type :hgvs}
    {:expr "...." :type :spdi}
    {:expr {"..." "..."} :type :cnv})
  (try
    (if (::copy-number? event)
      {:expression (-> event ::prioritized-expression)
       :normalized (get (get-vrs-variation-map
                         {:expression (-> event ::prioritized-expression :expr)
                          :expression-type (-> event ::prioritized-expression :type)})
                        :variation)
       :label (-> event ::prioritized-expression :expr)}
      (let [prefiltered-candidate-expressions (::canonical-candidate-expressions event)
            _ (log/debug :prefiltered-candidate-expressions prefiltered-candidate-expressions)

            ;; Temporary fix for timing out dup exprs
            ;; https://github.com/clingen-data-model/genegraph/issues/698

            ;; Remove variations that are deldup, not a CNV (previously set as a CNV if variant-length is at least 50bp),
            ;; and don't have a variant length defined. Since variant-length is not defined we can't safely asume it
            ;; is less than 50bp, we just don't know that it is more than 50bp (which would make it a CNV)

            ;; Return falsy if the expr is hgvs and the variant-length is not present
            ;; or is >= 50. Warn on >=50 because those should have been treated as CNV
            is-disallowed-hgvs-deldup
            (fn [{:keys [expr type location] :as expr-object}]
              (and (= :hgvs type)
                   (or (.contains expr "dup")
                       (.contains expr "del"))
                   (let [length (:variant-length location)]
                     (or (when (nil? length)
                           (log/warn :fn :normalization-canonical-expression
                                     :msg "HGVS dup/del had no length defined"
                                     :expr-object expr-object)
                           true)
                         (when (<= 50 length)
                           (log/warn :fn :normalize-canonical-expression
                                     :msg "HGVS expr >=50bp labeled as hgvs, not cnv"
                                     :expr-object expr-object
                                     :event-message (:genegraph.sink.event/value event))
                           true)))))

            ;; TODO: For deldup HGVS expressions that don't have a variant-length specified, we can try parsing
            ;; the HGVS expression ourselves to determine the minimum span in the reference sequence
            ;; non-iscn-cnv-too-long? (fn [{:keys [expr type]}]
            ;;                          (and (string? expr)
            ;;                               (-> expr
            ;;                                   hgvs/hgvs-parse-sequence-and-location
            ;;                                   (#(do (log/info :parsed-hgvs %) %))
            ;;                                   hgvs/parsed-expression-span
            ;;                                   (#(> % 50)))))

            candidate-expressions (filterv #(not (is-disallowed-hgvs-deldup %))
                                           prefiltered-candidate-expressions)]
        (when (not= candidate-expressions prefiltered-candidate-expressions)
          (log/warn :fn :normalize-canonical-expression
                    :msg "Removed some deldup candidate expressions"

                    #_#_:removed (set/difference (set candidate-expressions)
                                                 (set prefiltered-candidate-expressions))

                    ;; TODO why not set/difference?
                    ;; (set/difference (set candidate-expressions) (set prefiltered-candidate-expressions))
                    :removed (set/difference (set prefiltered-candidate-expressions)
                                             (set candidate-expressions))))

        (log/debug :candidate-expressions candidate-expressions)
        ;; each vrs-ret[:variation] is the structure in the 'variation', 'canonical_variaton' (or equivalent)
        ;; field in the normalization service response body
        ;; Try to get one that doesn't yield Text. If none, just use the first.
        (loop [unnormalized-exprs candidate-expressions
               normalized-exprs []]
          (if (empty? unnormalized-exprs)
            (first normalized-exprs)
            (let [ce (first unnormalized-exprs)
                  normed {:normalized (get (get-vrs-variation-map
                                            {:expression (-> ce :expr)
                                             :expression-type (-> ce :type)})
                                           :variation)
                          :expression ce
                          :label (-> ce :label)}]
              (if (not= "Text" (get-in normed [:normalized :canonical_context :type]))
                normed
                (recur (rest unnormalized-exprs)
                       (concat normalized-exprs [normed]))))))))
    (catch Exception e
      (log/error :fn :normalize-canonical-expression
                 :message "Exception normalizing canonical variation"
                 :candidate-expressions (::canonical-candidate-expressions event)
                 :prioritized-expression (::prioritized-expression event)
                 :ex-data (ex-data e)
                 :ex-message (ex-message e)
                 :ex-stacktrace (with-out-str (clojure.stacktrace/print-stack-trace e)))
      (update event :exception conj {:fn :normalize-canonical-expression
                                     :message "Exception normalizing canonical variation"
                                     :candidate-expressions (::canonical-candidate-expressions event)
                                     :prioritized-expression (::prioritized-expression event)
                                     :exception e}))))

(mount/defstate variation-data-db
  :start (rocksdb/open "variation-snapshot.db")
  :stop (rocksdb/close variation-data-db))

(defn get-variation-descriptor-by-clinvar-id
  "For a variation of the form \"40347\", return the variation from RocksDB"
  [variation-id release-date]
  (let [vrd-unversioned (str (ns-cg "VariationDescriptor_") variation-id)
        vd-iri (str vrd-unversioned "." release-date)
        variation-descriptor (docstore/get-document-raw-key variation-data-db vd-iri)]
    variation-descriptor))

(defn add-data-for-variation
  "Returns msg with :genegraph.annotate/data and :genegraph.annotate/data-contextualized added.

   Note this function may execute HTTP calls if the normalized version of canonical
   variation expressions are not already cached locally."
  [event]
  (let [event (variation-preprocess event)
        _ (log/debug :post-processed (select-keys event [::cnv
                                                         ::copy-number?
                                                         ::prioritized-expression
                                                         ::canonical-candidate-expressions]))
        message (:genegraph.transform.clinvar.core/parsed-value event)
        variation (:content message)
        vrd-unversioned (str (ns-cg "VariationDescriptor_") (:id variation))
        vd-iri (str vrd-unversioned "." (:release_date message))
        clinvar-variation-iri (str iri/clinvar-variation (:id variation))
        ;; normalize-canonical-expression must be called after variation-preprocess
        nce (normalize-canonical-expression event)]
    #_(log/debug :fn :add-data-for-variation :nce nce)
    (when (empty? nce)
      (log/error :fn :add-data-for-variation
                 :msg "Could not normalize canonical variation expression"
                 :value message
                 :event event))
    (let [data {:id vd-iri
                :type "CanonicalVariationDescriptor"
                :label (:name variation)
                :extensions (common/fields-to-extension-maps
                             (merge (dissoc variation :id :release_date :name :content)
                                    {:clinvar_variation clinvar-variation-iri}))
                :description (:name variation)
                :xrefs [(str (get prefix-ns-map "clinvar") (:id variation))
                        (str clinvar-variation-iri)]
                :alternate_labels []
                :members (->> (for [expr (::other-expressions event)]
                                (make-member-map vd-iri
                                                 (:expression expr)
                                                 (:syntax expr)
                                                 (:syntax-version expr)))
                              (into []))
                :subject_variation_descriptor ()
                :canonical_variation (:normalized nce)
                :record_metadata (merge {:type "RecordMetadata"
                                         :is_version_of vrd-unversioned
                                         :version (:release_date message)}
                                        (when (= "delete" (:event_type message))
                                          {:deleted true}))}]
      (-> event
          (assoc :genegraph.annotate/data data)
          ;; Add some info about how the canonical variation expression was selected
          (update-in
           [:genegraph.annotate/data :extensions]
           (fn [extensions] (concat extensions
                                    (common/fields-to-extension-maps
                                     {:canonical_expression
                                      (:expr (:expression nce))
                                      :candidate_expressions
                                      (map #(identity {;;:type (:type %)
                                                       :expression (:expr %)
                                                       :label (:label %)})
                                           (::canonical-candidate-expressions event))}
                                     {:expand-seqs? false}))))
          (assoc :genegraph.annotate/data-db variation-data-db
                 :genegraph.annotate/iri vd-iri
                 :genegraph.annotate/data-id vd-iri)
          add-contextualized))))

(defn record-metadata-resource-for-output
  [record-metadata-resource]
  (when record-metadata-resource
    {:type (q/ld1-> record-metadata-resource [:rdf/type])
     :is_version_of (q/ld1-> record-metadata-resource [:dc/is-version-of])
     :version (q/ld1-> record-metadata-resource [:owl/version-info])}))

(defn extension-resource-for-output
  [extension-resource]
  (when extension-resource
    (common/map-pop-out-lone-seq-values
     {:type (q/ld1-> extension-resource [:rdf/type])
      :name (q/ld1-> extension-resource [:vrs/name])
      :value (map #(-> % common/rdf-select-tree common/map-unnamespace-property-kw-keys)
                  (q/ld-> extension-resource [:rdf/value]))})))

(defn variation-member-resource-for-output
  [member-resource]
  (when member-resource
    {:type (q/ld1-> member-resource [:rdf/type])
     :expressions (letfn [(expression-resource-for-output
                            [expression-resource]
                            (when expression-resource
                              {:type (q/ld1-> expression-resource [:rdf/type])
                               :syntax (q/ld1-> expression-resource [:vrs/syntax])
                               :value (q/ld1-> expression-resource [:rdf/value])
                               :syntax_version (q/ld1-> expression-resource [:vrs/syntax-version])}))]
                    (map expression-resource-for-output
                         (q/ld-> member-resource [:vrs/expressions])))}))
(def class-uri->keyword
  (into {} (map (fn [[k v]] [(str k) v])
                names/class-uri->keyword)))

(defn class-kw
  [qualified-class-name]
  (let [kw (get class-uri->keyword (str qualified-class-name))]
    (when (nil? kw) (throw (ex-info "Class name not found" {:class-name qualified-class-name})))
    kw))

(defn number-resource-for-output
  [number-resource]
  (when number-resource
    {:type (q/ld1-> number-resource [:rdf/type])
     :value (q/ld1-> number-resource [:rdf/value])}))

(defn number-or-range-resource-for-output
  [quantity-resource]
  (let [quantity-type (class-kw (q/ld1-> quantity-resource [:rdf/type]))]
    (case quantity-type
      :vrs/DefiniteRange {:type (q/ld1-> quantity-resource [:rdf/type])
                          :min (q/ld1-> quantity-resource [:vrs/min])
                          :max (q/ld1-> quantity-resource [:vrs/max])}
      :vrs/IndefiniteRange {:type (q/ld1-> quantity-resource [:rdf/type])
                            :value (q/ld1-> quantity-resource [:rdf/value])
                            :comparator (q/ld1-> quantity-resource [:vrs/comparator])}
      :vrs/Number (number-resource-for-output quantity-resource)
      (let [ex (ex-info "Unrecognized quantity type"
                        {:fn :number-or-range-resource-for-output
                         :quantity-type quantity-type})]
        (log/error :message (ex-message ex) :data (ex-data ex))
        (throw ex)))))

(defn sequence-location-resource-for-output
  [sequence-location-resource]
  (when sequence-location-resource
    {:id (str sequence-location-resource)
     :type (q/ld1-> sequence-location-resource [:rdf/type])
     :sequence_id (q/ld1-> sequence-location-resource [:vrs/sequence-id])
     :start (number-or-range-resource-for-output
             (q/ld1-> sequence-location-resource [:vrs/start]))
     :end (number-or-range-resource-for-output
           (q/ld1-> sequence-location-resource [:vrs/end]))}))

(defn chromosome-location-resource-for-output
  [chromosome-location-resource]
  (when chromosome-location-resource
    {:id (str chromosome-location-resource)
     :type (q/ld1-> chromosome-location-resource [:rdf/type])
     :species_id (q/ld1-> chromosome-location-resource [:vrs/species-id])
     :chr (q/ld1-> chromosome-location-resource [:vrs/chr])
     ;; start and end here are HumanCytoband, which is just a regex-constrained string
     :start (q/ld1-> chromosome-location-resource [:vrs/start])
     :end (q/ld1-> chromosome-location-resource [:vrs/end])}))

(defn derived-sequence-expression-for-output
  [dse-resource]
  (when dse-resource
    {:type (q/ld1-> dse-resource [:rdf/type])
     :location (sequence-location-resource-for-output
                (q/ld1-> dse-resource [:vrs/location]))
     :reverse_complement (Boolean/valueOf (q/ld1-> dse-resource [:vrs/reverse_complement]))}))

(defn literal-sequence-expression-for-output
  [lse-resource]
  (when lse-resource
    {:type (q/ld1-> lse-resource [:rdf/type])
     :sequence (q/ld1-> lse-resource [:vrs/sequence])}))

(defn composed-sequence-expression-for-output
  [cse-resource]
  ;; TODO
  ())

(defn repeated-sequence-expression-for-output
  [rse-resource]
  (when rse-resource
    {:type (q/ld1-> rse-resource [:rdf/type])
     :seq_expr (let [seq-expr-r (q/ld1-> rse-resource [:vrs/seq-expr])
                     seq-expr-type (class-kw (q/ld1-> seq-expr-r [:rdf/type]))]
                 (case seq-expr-type
                   :vrs/LiteralSequenceExpression (literal-sequence-expression-for-output
                                                   seq-expr-r) ;; TODO
                   :vrs/DerivedSequenceExpression (derived-sequence-expression-for-output
                                                   seq-expr-r) ;; TODO
                   (let [ex (ex-info "Unrecognized sequence expression type"
                                     {:fn :repeated-sequence-expression-for-output
                                      :seq-expr-type seq-expr-type})]
                     (log/error :message (ex-message ex) :data (ex-data ex))
                     (throw ex))))
     :count (let [count-r (q/ld1-> rse-resource [:vrs/count])
                  count-type (class-kw (q/ld1-> count-r [:rdf/type]))]
              (case count-type
                :vrs/DefiniteRange {:type (q/ld1-> count-r [:rdf/type])
                                    :min (q/ld1-> count-r [:vrs/min])
                                    :max (q/ld1-> count-r [:vrs/max])}
                :vrs/IndefiniteRange {:type (q/ld1-> count-r [:rdf/type])
                                      :value (q/ld1-> count-r [:rdf/value])
                                      :comparator (q/ld1-> count-r [:vrs/comparator])}
                :vrs/Number (number-resource-for-output count-r)
                (let [ex (ex-info "Unrecognized count type"
                                  {:fn :repeated-sequence-expression-for-output
                                   :count-type count-type})]
                  (log/error :message (ex-message ex) :data (ex-data ex))
                  (throw ex))))}))

(defn allele-resource-for-output
  [allele-resource]
  (log/debug :fn :allele-resource-for-output :allele-resource allele-resource)
  (when allele-resource
    {:id (str allele-resource)
     :type (q/ld1-> allele-resource [:rdf/type])
     :location (sequence-location-resource-for-output
                (q/ld1-> allele-resource [:vrs/location]))
     :state (let [state-resource (q/ld1-> allele-resource [:vrs/state])
                  state-type (class-kw (q/ld1-> state-resource [:rdf/type]))]
              (case state-type
                :vrs/LiteralSequenceExpression (literal-sequence-expression-for-output
                                                state-resource)
                :vrs/RepeatedSequenceExpression (repeated-sequence-expression-for-output
                                                 state-resource)
                (let [ex (ex-info "Unrecognized state type"
                                  {:fn :allele-resource-for-output
                                   :state-type state-type})]
                  (log/error :message (ex-message ex) :data (ex-data ex))
                  (throw ex))))}))

(defn text-variation-resource-for-output
  [text-variation-resource]
  (when text-variation-resource
    {:id (str text-variation-resource)
     :type (q/ld1-> text-variation-resource [:rdf/type])
     :definition (q/ld1-> text-variation-resource [:vrs/definition])}))

(defn relative-cnv-resource-for-output
  [cnv-resource]
  (when cnv-resource
    {:id (str cnv-resource)
     :type (q/ld1-> cnv-resource [:rdf/type])
     :location (let [location-r (q/ld1-> cnv-resource [:vrs/location])
                     location-type (class-kw (q/ld1-> location-r [:rdf/type]))]
                 (case location-type
                   :vrs/SequenceLocation (sequence-location-resource-for-output location-r)
                   :vrs/ChromosomeLocation (chromosome-location-resource-for-output location-r)
                   (do (log/warn :fn :relative-cnv-resource-for-output
                                 :msg "RelativeCopyNumber.location not a ChromosomeLocation or SequenceLocation"
                                 :cnv-resource cnv-resource
                                 :location-r location-r
                                 :location-type location-type)
                       (str location-r))))
     :relative_copy_class (q/ld1-> cnv-resource [:vrs/relative-copy-class])}))

(defn canonical-context-resource-for-output
  [context-resource]
  (log/debug :fn :canonical-context-resource-for-output
             :context-resource context-resource)
  (let [t (class-kw (q/ld1-> context-resource [:rdf/type]))]
    (case t
      :vrs/Allele (allele-resource-for-output context-resource)
      :vrs/Text (text-variation-resource-for-output context-resource)
      :vrs/RelativeCopyNumber (relative-cnv-resource-for-output context-resource)
      (let [ex (ex-info "Unrecognized canonical context type"
                        {:fn :canonical-context-resource-for-output
                         :canonical-context-type t})]
        (log/error :message (ex-message ex) :data (ex-data ex))
        (throw ex)))))

(defn canonical-variation-resource-for-output
  [variation-resource]
  (log/debug :fn :canonical-variation-resource-for-output
             :variation-resource variation-resource)
  (when variation-resource
    {:id (str variation-resource)
     :type (q/ld1-> variation-resource [:rdf/type])
     :canonical_context (some-> (q/ld1-> variation-resource [:vrs/canonical-context])
                                canonical-context-resource-for-output)}))

(defn variation-resource-for-output
  [variation-resource]
  (let [variation-type (class-kw (q/ld1-> variation-resource [:rdf/type]))]
    (case variation-type
      :vrs/CanonicalVariation (canonical-variation-resource-for-output variation-resource)
      (let [ex (ex-info "Unrecognized variation type"
                        {:fn :variation-resource-for-output
                         :variation-type variation-type})]
        (log/error :message (ex-message ex) :data (ex-data ex))
        (throw ex)))))

(defn variation-descriptor-resource-for-output
  "Takes a VariationDescriptor resource and returns a GA4GH edn structure"
  [descriptor-resource]
  (log/debug :fn :variation-descriptor-resource-for-output
             :descriptor-resource descriptor-resource)
  {:id (str descriptor-resource)
   :type (q/ld1-> descriptor-resource [:rdf/type])
   :label (q/ld1-> descriptor-resource [:rdfs/label])
   :extensions (->> (q/ld-> descriptor-resource [:vrs/extensions])
                    (map extension-resource-for-output))
   :description (q/ld1-> descriptor-resource [:vrs/description])
   :xrefs (q/ld-> descriptor-resource [:vrs/xrefs])
   :alternate_labels nil
   :members (->> (q/ld-> descriptor-resource [:vrs/members])
                 (map variation-member-resource-for-output))
   :subject_variation_descriptor nil
   :canonical_variation (-> (q/ld1-> descriptor-resource [:vrs/canonical-variation])
                            variation-resource-for-output)
   ;; TODO there is no record_metadata field on CanonicalVariationDescriptor or its super classes
   ;; https://github.com/ga4gh/vrs/blob/metaschema-update/schema/core.json
   #_#_:record_metadata (-> (q/ld1-> descriptor-resource [:vrs/record-metadata])
                            record-metadata-resource-for-output)})

(defn variation-descriptor-for-output
  "Annotates event with data previously stored in rocksdb for outputting variation descriptors.
  Event is the full event stored in rocks."
  [event]
  (let [data (:genegraph.annotate/data event)]
    (assoc event :genegraph.annotate/output
           {:id (:id data)
            :type (:type data)
            :label (:label data)
            :extensions (:extensions data)
            :description (:description data)
            :xrefs (:xrefs data)
            :alternate_labels nil
            :members (:members data)
            :subject_variation_descriptor nil
            :canonical_variation (:canonical_variation data)})))

(def variation-context
  {"@context"
   {;; Properties
    "is_version_of" {"@id" (str (get local-property-names :dc/is-version-of))
                     "@type" "@id"}
    "type" {"@id" "@type"
            "@type" "@id"}
    "name" {"@id" (str (get local-property-names :vrs/name))}

    ;;"value" {"@id" "@value"}
    "value" {"@id" "rdf:value"}
    "label" {"@id" "rdfs:label"}

    "replaces" {"@id" (str (get local-property-names :dc/replaces))
                "@type" "@id"}
    "is_replaced_by" {"@id" (str (get local-property-names :dc/is-replaced-by))
                      "@type" "@id"}
    "version" {"@id" (str (get local-property-names :owl/version-info))}
    "id" {"@id" "@id"
          "@type" "@id"}
    ;; "_id" {"@id" "@id"
    ;;        "@type" "@id"}

    ;; custom properties
    "deleted" {"@id" (str (get prefix-ns-map "cgterms") "deleted")}

    ;; eliminate vrs prefixes on vrs variation terms
    ;; VRS properties
    "variation" {"@id" (str (get prefix-ns-map "vrs") "variation")}
    "complement" {"@id" (str (get prefix-ns-map "vrs") "complement")}
    "interval" {"@id" (str (get prefix-ns-map "vrs") "interval")}
    "start" {"@id" (str (get prefix-ns-map "vrs") "start")}
    "end" {"@id" (str (get prefix-ns-map "vrs") "end")}
    "location" {"@id" (str (get prefix-ns-map "vrs") "location")}
    "state" {"@id" (str (get prefix-ns-map "vrs") "state")}
    "sequence_id" {"@id" (str (get prefix-ns-map "vrs") "sequence_id")}
    "sequence" {"@id" (str (get prefix-ns-map "vrs") "sequence")}
    "record_metadata" {"@id" (str (get prefix-ns-map "vrs") "record_metadata")}
    "xrefs" {"@id" (str (get prefix-ns-map "vrs") "xrefs")}
    "description" {"@id" (str (get prefix-ns-map "vrs") "description")}
    "syntax" {"@id" (str (get prefix-ns-map "vrs") "syntax")}
    "syntax_version" {"@id" (str (get prefix-ns-map "vrs") "syntax_version")}
    "expression" {"@id" (str (get prefix-ns-map "vrs") "expression")}
    "canonical_context" {"@id" (str (get prefix-ns-map "vrs") "canonical_context")}
    "canonical_variation" {"@id" (str (get prefix-ns-map "vrs") "canonical_variation")}
    "definition" {"@id" (str (get prefix-ns-map "vrs") "definition")}
    "seq_expr" {"@id" (str (get prefix-ns-map "vrs") "seq_expr")}
    "count" {"@id" (str (get prefix-ns-map "vrs") "count")}
    "min" {"@id" (str (get prefix-ns-map "vrs") "min")}
    "max" {"@id" (str (get prefix-ns-map "vrs") "max")}
    "comparator" {"@id" (str (get prefix-ns-map "vrs") "comparator")}
    "species_id" {"@id" (str (get prefix-ns-map "vrs") "species_id")}
    "chr" {"@id" (str (get prefix-ns-map "vrs") "chr")}
    "relative_copy_class" {"@id" (str (get prefix-ns-map "vrs") "relative_copy_class")}



    ;; map plurals to known guaranteed array types
    "members" {"@id" (str (get local-property-names :vrs/members))
               "@container" "@set"}
    "extensions" {"@id" (str (get local-property-names :vrs/extensions))
                  "@container" "@set"}
    "expressions" {"@id" (str (get local-property-names :vrs/expressions))
                   "@container" "@set"}

    ;; VRS entities
    "Extension" {"@id" (str (get local-class-names :vrs/Extension))}
    "CanonicalVariationDescriptor" {"@id" (str (get local-class-names :vrs/CanonicalVariationDescriptor))
                                    "@type" "@id"}
    "CanonicalVariation" {"@id" (str (get local-class-names :vrs/CanonicalVariation))
                          "@type" "@id"}
    "Allele" {"@id" (str (get local-class-names :vrs/Allele))
              "@type" "@id"}
    "Text" {"@id" (str (get local-class-names :vrs/Text))
            "@type" "@id"}
    "RelativeCopyNumber" {"@id" (str (get local-class-names :vrs/RelativeCopyNumber))
                          "@type" "@id"}
    "SequenceLocation" {"@id" (str (get local-class-names :vrs/SequenceLocation))
                        "@type" "@id"}
    "SequenceInterval" {"@id" (str (get local-class-names :vrs/SequenceInterval))
                        "@type" "@id"}
    "ChromosomeLocation" {"@id" (str (get local-class-names :vrs/ChromosomeLocation))
                          "@type" "@id"}
    "HumanCytoband" {"@id" (str (get local-class-names :vrs/HumanCytoband))
                     "@type" "@id"}
    "Number" {"@id" (str (get local-class-names :vrs/Number))
              "@type" "@id"}
    "LiteralSequenceExpression" {"@id" (str (get local-class-names :vrs/LiteralSequenceExpression))
                                 "@type" "@id"}
    "RepeatedSequenceExpression" {"@id" (str (get local-class-names :vrs/RepeatedSequenceExpression))
                                  "@type" "@id"}
    "DerivedSequenceExpression" {"@id" (str (get local-class-names :vrs/DerivedSequenceExpression))
                                 "@type" "@id"}
    "VariationMember" {"@id" (str (get local-class-names :vrs/VariationMember))
                       "@type" "@id"}
    "RecordMetadata" {"@id" (str (get local-class-names :vrs/RecordMetadata))
                      "@type" "@id"}
    "Expression" {"@id" (str (get local-class-names :vrs/Expression))
                  "@type" "@id"}

    ;; Prefixes
    ;; "https://vrs.ga4gh.org/terms/"
    "rdf" {"@id" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
           "@prefix" true}
    "rdfs" {"@id" "http://www.w3.org/2000/01/rdf-schema#"
            "@prefix" true}
    "vrs" {"@id" (get prefix-ns-map "vrs")
           "@prefix" true}
    "cgterms" {"@id" (get prefix-ns-map "cgterms")
               "@prefix" true}}})

(def variation-descriptor-query
  "
query($variation_iri:String) {
  variation_descriptor_query(variation_iri: $variation_iri) {
    id: iri
    type: __typename
    ... on CategoricalVariationDescriptor {
      label
      value {
        id: iri
        type: __typename
        ... on CanonicalVariation {
          complement
          variation {
            ... alleleFields
          }
        }
        ... on Allele {
          ...alleleFields
        }
      }
      xrefs
      members {
        id: iri
        type: __typename
        expressions {
          type: __typename
          value
          syntax
          syntax_version
        }
      }
      extensions {
        type: __typename
        name
        value
      }
    }
  }
}

fragment alleleFields on Allele {
  location {
    id: iri
    type: __typename
    interval {
      type: __typename
      start {
        type: __typename
        value
      }
      end {
        type: __typename
        value
      }
    }
  }
}
")


(defmethod common/clinvar-add-event-graphql :variation [event]
  (let [iri (:genegraph.annotate/iri event)]
    (assoc event :graphql-params {:query variation-descriptor-query
                                  :variables {:variation_iri (str iri)}})))


(defmethod common/clinvar-model-to-jsonld :variation [event]
  (log/info :fn :clinvar-model-to-jsonld :event event)
  (let [model (::q/model event)]
    (-> model
        (jsonld/model-to-jsonld)
        (jsonld/jsonld-to-jsonld-framed (json/generate-string variation-frame))
        ;; TODO may consider adding scoped context to the vrs variation object, with vocab=vrs
        (jsonld/jsonld-compact (json/generate-string (merge variation-context))))))
