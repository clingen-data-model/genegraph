(ns genegraph.transform.clinvar.ga4gh
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.string :as str]
            [genegraph.annotate :as ann]
            [genegraph.database.query :as q]
            [genegraph.database.util :refer [tx write-tx]]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.server]
            [genegraph.sink.document-store :as docstore]
            [genegraph.sink.event :as event]
            [genegraph.source.registry.rocks-registry :as rocks-registry]
            [genegraph.transform.clinvar.clinical-assertion :as ca]
            [genegraph.transform.clinvar.common
             :as common
             :refer [map-compact-namespaced-values
                     map-rdf-resource-values-to-str map-unnamespace-values]]
            [genegraph.transform.clinvar.iri :refer [ns-cg
                                                     parse-clinvar-resource-iri]]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.clinvar.variation :as variation]
            [genegraph.transform.types :as xform-types]
            [genegraph.util.fs :refer [gzip-file-reader]]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [genegraph.transform.clinvar.core :as clinvar]))

(def stop-removing-unused [#'write-tx #'tx #'pprint #'util/parse-nested-content])

(defn start-states! []
  (mount/start
   #'genegraph.database.instance/db
   #'genegraph.database.property-store/property-store
   #'genegraph.transform.clinvar.cancervariants/cache-db
   #'genegraph.transform.clinvar.variation/variation-data-db
   #'genegraph.sink.event-recorder/event-database
   #'genegraph.sink.document-store/db
   #'genegraph.transform.clinvar.variation/variation-data-db
   #'genegraph.transform.clinvar.clinical-assertion/trait-data-db
   #'genegraph.transform.clinvar.clinical-assertion/trait-set-data-db
   #'genegraph.transform.clinvar.clinical-assertion/clinical-assertion-data-db
   #'rocks-registry/db
   #'rocks-registry/server))

(defn eventify [input-map]
  ;; Mostly replicating
  ;; (map #(stream/consumer-record-to-event % :clinvar-raw))
  ;; but without some fields that are not used here
  {:genegraph.annotate/format :clinvar-raw
   :genegraph.sink.event/key nil
   :genegraph.sink.event/value (json/generate-string input-map)
   ::topic "clinvar-raw"
   ::partition 0})

(def topic-file "clinvar-raw-local-filtered-vcepvars.txt")

(defn get-message-seq []
  (-> topic-file
      io/reader
      line-seq
      (->> (map #(json/parse-string % true)))))


(defn add-data-catch-exceptions [event]
  (try
    (xform-types/add-data event)
    (catch Exception ex
      (log/error :fn :add-data-catch-exceptions
                 :msg "Exception adding data to event"
                 :event event
                 :exception ex)
      event)))


(defn message-proccess-with-jena!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ann/add-action
      add-data-catch-exceptions
      xform-types/add-model
      docstore/store-document-raw-key
      event/add-to-db!))

(defn message-proccess-with-rocksdb!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ann/add-action
      add-data-catch-exceptions
      docstore/store-document-raw-key))

(defn message-proccess-no-db!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ; needed for add-to-db! to work
      ann/add-action
      add-data-catch-exceptions))


(defn clinvar-add-iri [event]
  ;; TODO doesn't work for types without ids
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        content (:content message)]
    (assoc event :genegraph.annotate/iri
           (str (ns-cg (:entity_type content))
                "_" (:id content)
                "." (:release_date message)))))

(defn process-topic-file-parallel-no-output [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (write-tx
     (doseq [event (->> messages
                        (pmap message-proccess-with-rocksdb!))]
       (let [clinvar-type (:genegraph.transform.clinvar/format event)])))))

(defn process-topic-file [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (with-open [statement-writer (io/writer (str input-filename "-output-statements"))
                variation-descriptor-writer (io/writer (str input-filename "-output-variation-descriptors"))
                other-writer (io/writer (str input-filename "-output-other"))]
      (write-tx
       (doseq [event (->> (map message-proccess-with-jena! messages)
                          #_(take 10))]
         (let [clinvar-type (:genegraph.transform.clinvar/format event)
               ;;_ (log/info :clinvar-type clinvar-type)
               ;;_ (log/info :event event)
               writer (case clinvar-type
                        :clinical_assertion statement-writer
                        :variation variation-descriptor-writer
                        other-writer)]
           (.write writer (-> event
                              :genegraph.annotate/data-contextualized
                              (dissoc "@context")
                              common/map-remove-nil-values
                              (json/generate-string)))
           (.write writer "\n")))))))

(defn snapshot-latest-statements-of-type
  [type-kw]
  (try
    (let [type-name (name type-kw)
          output-filename (format "statements-%s.txt" type-name)]
      (with-open [writer (io/writer output-filename)]
      ;; TODO look at group by for this.
      ;; Just want each iri for latest release_date for each is-version-of
        (tx
         (let [unversioned-resources
               (q/select (str/join " " ["select distinct ?vof where { "
                                        "?i a ?type . "
                                        "?i :vrs/record-metadata ?rmd . "
                                        "?rmd :dc/is-version-of ?vof . "
                                        #_"?i :vrs/extensions ?ext . "
                                        #_"?ext :vrs/name \"local_key\" . "
                                        " } "])
                         {:type type-kw})
               #_#__ (log/debug :fn :snapshot-latest-statements
                                :msg (str "unversioned-resources count: "
                                          (count unversioned-resources))
                                :resources (map str unversioned-resources))
               latest-versioned-resources
               (map (fn [vof]
                      (let [rs (q/select (str "select ?i where { "
                                              "?i a ?type . "
                                              "?i :vrs/record-metadata ?rmd . "
                                              "?rmd :dc/is-version-of ?vof . "
                                              "?rmd :owl/version-info ?release_date . } "
                                              "order by desc(?release_date) "
                                              "limit 1")
                                         {:type type-kw
                                          :vof vof})]
                        (if (< 1 (count rs))
                          (log/error :msg "More than 1 statement returned"
                                     :vof vof :rs rs)
                          (first rs))))
                    (->> unversioned-resources
                         #_(take 10)))]
           (doseq [[i statement-resource] (->> latest-versioned-resources
                                               (map-indexed vector))]
             (log/info :latest-statement-resource statement-resource)
             (try
               (let [statement-output (ca/clinical-assertion-resource-for-output statement-resource)
                     _ (log/debug :msg "Post processing output...")
                     post-processed-output (-> statement-output
                                               map-rdf-resource-values-to-str
                                               common/map-remove-nil-values
                                               (map-unnamespace-values (set [:type]))
                                               (map-compact-namespaced-values))]
                 (log/info :progress (format "%d/%d" (inc i) (count latest-versioned-resources))
                           :post-processed-output post-processed-output)
                 (.write writer (json/generate-string post-processed-output))
                 (.write writer "\n"))
               (catch Exception e
                 (print-stack-trace e)
                 (log/error :msg "Failed to output statement"
                            :statement-resource statement-resource))))))))
    (catch Exception e
      (print-stack-trace e))))

(defn snapshot-latest-rocks-data-of-type
  [type-kw]
  (try
    ;; TODO look at group by for this.
    ;; Just want each iri for latest release_date for each is-version-of
    (tx
     (let [unversioned-resources
           (q/select (str/join " " ["select distinct ?vof where { "
                                    "?i a ?type . "
                                    "?i :vrs/record-metadata ?rmd . "
                                    "?rmd :dc/is-version-of ?vof . "
                                    #_"?i :vrs/extensions ?ext . "
                                    #_"?ext :vrs/name \"local_key\" . "
                                    " } "])
                     {:type type-kw})
           latest-versioned-resources
           (map (fn [vof]
                  (let [rs (q/select (str "select ?i where { "
                                          "?i a ?type . "
                                          "?i :vrs/record-metadata ?rmd . "
                                          "?rmd :dc/is-version-of ?vof . "
                                          "?rmd :owl/version-info ?release_date . } "
                                          "order by desc(?release_date) "
                                          "limit 1")
                                     {:type type-kw
                                      :vof vof})]
                    (if (< 1 (count rs))
                      (log/error :msg "More than 1 statement returned"
                                 :vof vof :rs rs)
                      (first rs))))
                unversioned-resources)]
       latest-versioned-resources))
    (catch Exception e
      (print-stack-trace e))))

(defn write-latest-statement-snapshots [type-kw]
  (let [type-name (name type-kw)
        output-filename (format "statements-%s.txt" type-name)]
    (try
      (with-open [writer (io/writer output-filename)]
        (doall
         (map (fn [statement-resource]
                (let [statement-iri (str statement-resource)
                      event (docstore/get-document ca/clinical-assertion-data-db statement-iri)
                      output (ca/clinical-assertion-for-output event)]
                  (.write writer (json/generate-string (-> output
                                                           :genegraph.annotate/output
                                                           common/map-remove-nil-values)))
                  (.write writer "\n")))
              (snapshot-latest-rocks-data-of-type type-kw))))
      (catch Exception e
        (print-stack-trace e)))))

(defn write-latest-variation-snapshots
  "Write variation descriptors from rocks db"
  []
  (try
    (with-open [writer (io/writer "x-variation-descriptors.txt")]
      (doall
       (map (fn [variation-resource]
              (log/info :fn :write-latest-variation-snapshots :variation-resource variation-resource)
              (let [variation-iri (str variation-resource)
                    event (docstore/get-document variation/variation-data-db variation-iri)
                    _ (log/info :fn :write-latest-variation-snapshots :event event)
                    output (variation/variation-descriptor-for-output event)]
                (.write writer (json/generate-string (->  output
                                                          :genegraph.annotate/output
                                                          common/map-remove-nil-values)))
                (.write writer "\n")))
            (take 5 (snapshot-latest-rocks-data-of-type :vrs/CanonicalVariationDescriptor)))))))

(defn snapshot-latest-statements-of-type-parallel
  [type-kw]
  (try
    (let [type-name (name type-kw)
          output-filename (format "statements-%s.txt" type-name)]
      (with-open [writer (io/writer output-filename)]
        ;; TODO look at group by for this.
        ;; Just want each iri for latest release_date for each is-version-of
        (tx
         (let [unversioned-resources
               (q/select (str "select distinct ?vof where { "
                              "?i a ?type . "
                              "?i :vrs/record-metadata ?rmd . "
                              "?rmd :dc/is-version-of ?vof . } ")
                         {:type type-kw})
               latest-versioned-resources
               (pmap (fn [vof]
                       (let [rs (q/select (str "select ?i where { "
                                               "?i a ?type . "
                                               "?i :vrs/record-metadata ?rmd . "
                                               "?rmd :dc/is-version-of ?vof . "
                                               "?rmd :owl/version-info ?release_date . } "
                                               "order by desc(?release_date) "
                                               "limit 1")
                                          {:type type-kw
                                           :vof vof})]
                         (if (< 1 (count rs))
                           (log/error :msg "More than 1 statement returned"
                                      :vof vof :rs rs)
                           (first rs))))
                     (->> unversioned-resources #_(take 100)))]
           (doseq [[i post-processed-output]
                   (->> latest-versioned-resources
                        (pmap #(-> (ca/clinical-assertion-resource-for-output %)
                                   map-rdf-resource-values-to-str
                                   common/map-remove-nil-values
                                   (map-unnamespace-values (set [:type]))
                                   (map-compact-namespaced-values)))
                        (map-indexed vector))]
             (try
               (log/info :progress (format "Writing processed output %d/%d"
                                           (inc i)
                                           (count latest-versioned-resources))
                         :post-processed-output post-processed-output)
               (.write writer (json/generate-string post-processed-output))
               (.write writer "\n")
               (catch Exception e
                 (print-stack-trace e)
                 (log/error :msg "Failed to output statement"
                            :statement-resource post-processed-output))))))))
    (catch Exception e
      (print-stack-trace e))))

(defn snapshot-latest-statements []
  (let [stmt-types [:vrs/VariationGermlinePathogenicityStatement
                    :vrs/ClinVarDrugResponseStatement
                    :vrs/ClinVarOtherStatement]]
    (doseq [t stmt-types]
      (snapshot-latest-statements-of-type t))))


(defn snapshot-latest-variations []
  (try
    (let [output-filename "variation-descriptors.txt"]
      (with-open [writer (io/writer output-filename)]
      ;; TODO look at group by for this.
      ;; Just want each iri for latest release_date for each is-version-of
        (tx
         (let [unversioned-resources
               (q/select (str "select distinct ?vof where { "
                              "?i a :vrs/CanonicalVariationDescriptor . "
                              "?i :vrs/record-metadata ?rmd . "
                              "?rmd :dc/is-version-of ?vof . "
                              "} "))
               _ (log/info :fn :snapshot-latest-variations
                           :msg (str "unversioned-resources count: "
                                     (count unversioned-resources)))
               latest-versioned-resources
               (->> unversioned-resources
                    #_(take 1)
                    (map (fn [vof]
                           (log/info :vof vof)
                           (let [rs (q/select (->> ["select ?i where" "{"
                                                    ["{" ["select ?i ?rmd where" "{"
                                                          "?i a :vrs/CanonicalVariationDescriptor ."
                                                          "?i :vrs/record-metadata ?rmd ."
                                                          "?rmd :dc/is-version-of ?vof ."
                                                          "?rmd :owl/version-info ?release_date ."
                                                          "}"]
                                                     "order by desc(?release_date)"
                                                     "limit 1"
                                                     "}"]
                                                    ["filter not exists" "{" "?rmd :cg/deleted true" "}"]
                                                    "}"
                                                    "order by asc(?i)"]
                                                   flatten
                                                   (str/join " "))
                                              {:vof vof})]
                             (if (< 1 (count rs))
                               (log/error :msg "More than 1 statement returned"
                                          :vof vof :rs rs)
                               (first rs)))))
                    (filter (comp not nil?)))]
           (doseq [descriptor-resource (->> latest-versioned-resources)]
             (try
               (let [descriptor-output (variation/variation-descriptor-resource-for-output descriptor-resource)
                     #_#__ (log/info :descriptor-output descriptor-output)
                     post-processed-output (-> descriptor-output
                                               map-rdf-resource-values-to-str
                                               common/map-remove-nil-values
                                               (map-unnamespace-values #{:type})
                                               (map-compact-namespaced-values))]

                 (log/info :msg "Writing descriptor"
                           :id (:id post-processed-output))
                 (.write writer (json/generate-string post-processed-output))
                 (.write writer "\n"))
               (catch Exception e
                 (print-stack-trace e)
                 (log/error :msg "Failed to output variation descriptor"
                            :descriptor-resource descriptor-resource))))))))
    (catch Exception e
      (print-stack-trace e))))

(defn snapshot-variation-db []
  (let [db genegraph.transform.clinvar.variation/variation-data-db
        out-fname "variation-data-db-snapshot.ndjson"]
    (with-open [writer (io/writer (io/file out-fname))]
      (doseq [[entry-k entry-v] (rocksdb/entire-db-entry-seq db)]
        (let [data (:genegraph.annotate/data entry-v)]
          (.write writer (json/generate-string data))
          (.write writer "\n"))))))

(defn slashjoin [& args]
  (reduce (fn [agg val]
            (str agg "/" val))
          args))

(defn cv-transform-test-fname [& relative-path-segs]
  (str "test/genegraph/transform/clinvar/test-inputs/" (apply slashjoin relative-path-segs)))

(defn not-deleted?
  "Returns true if .record_metadata.deleted is not truthy"
  [[entry-k entry-v]]
  (let [deleted? (get-in entry-v [:record_metadata :deleted])]
    (when deleted? (log/info :entry-id (:id entry-v) :deleted deleted?))
    (not deleted?)))

#_(defn latest-versions-seq
    "For key-value entries where the key is formatted like <prefix><id>.<version>,
   get the last entry for each <prefix><id>. Assumes versions are lexicographically sortable.
   Relies on RocksDB itself being sorted on the byte array keys."
    ([db]
     (latest-versions-seq db (rocksdb/entire-db-entry-seq db)))
    ([db remaining]
  ;; I'm getting the iri-prefix by parsing the key of the next entry in the db.
  ;; If we are consistent on the use of RecordMetadata for top level objects that
  ;; are used in the output, we could use the version_of value from there as well
     (when (seq remaining)
       (let [[entry-k entry-v] (first remaining)
             thawed-k (String. entry-k)
             {:keys [id version ns-prefix type-prefix] :as parsed}
             (parse-clinvar-resource-iri thawed-k)
             variation-descriptor-iri-prefix (str ns-prefix type-prefix)
             iri-prefix (str variation-descriptor-iri-prefix id ".")
             prefix-seq (rocksdb/raw-prefix-entry-seq db (.getBytes iri-prefix))
             last-entry (last prefix-seq)]
         (log/debug :iri-prefix iri-prefix
                    :parsed-iri parsed
                    :skip-count (count prefix-seq))
         (let [[last-k last-v] last-entry
               deserialized-last-entry [(String. last-k) last-v]]
           (lazy-cat [deserialized-last-entry]
                     (latest-versions-seq db (drop (count prefix-seq) remaining))))))))

;; TODO
;; One idea is to extend the lazy seqs returned from rocksdb.clj to make them implement java Closeable
;; so caller can close them instead of needing to construct an iterator directly in order to close it
#_(reify
    java.lang.AutoCloseable
    (close [this] (.close iter))

    clojure.lang.ISeq
    (first [this] (.first out))
    (next [this] (.next out))
    (more [this] (.more out))
    (cons [this o] (.cons out o)))

(defn latest-versions-seq-iterator-based
  "For key-value entries where the key is formatted like <prefix><id>.<version>,
   get the last entry for each <prefix><id>. Assumes versions are lexicographically sortable.
   Relies on RocksDB itself being sorted on the byte array keys.

   If an iterator is not passed in, opens one and returns it. Caller must close this ASAP"
  ([db] (let [iter (rocksdb/entire-db-iter db)
              out (latest-versions-seq-iterator-based db iter)]
          {:iter iter
           :out out}))
  ([db iter]
  ;; I'm getting the iri-prefix by parsing the key of the next entry in the db.
  ;; If we are consistent on the use of RecordMetadata for top level objects that
  ;; are used in the output, we could use the version_of value from there as well
   (letfn [(unversioned-iri-prefix [deserialized-entry-k]
             (let [{:keys [id version ns-prefix type-prefix] :as parsed}
                   (parse-clinvar-resource-iri deserialized-entry-k)
                   typed-iri-prefix (str ns-prefix type-prefix)
                   iri-prefix (str typed-iri-prefix id ".")]
               iri-prefix))
           (get-first-matching [pred coll]
             (reduce (fn [agg val]
                       (if ((comp not pred) val)
                         (reduced agg)
                         (concat agg [val])))
                     [] coll))]
     (if (.isValid iter)
       (let [s (rocksdb/rocks-entry-iterator-seq iter)
             [entry-k entry-v] (first s)
             thawed-k (String. entry-k)
             iri-prefix (unversioned-iri-prefix thawed-k)
           ;;_ (log/info :iri-prefix iri-prefix)
             prefix-seq (get-first-matching (fn [[k v]]
                                              (let [k (String. k)
                                                    k-iri-prefix (unversioned-iri-prefix k)]
                                                (= iri-prefix k-iri-prefix)))
                                            s)
           ;;_ (log/info :prefix-seq prefix-seq)
             [last-k last-v] (last prefix-seq)
             last-k (String. last-k)]
         (log/debug :iri-prefix iri-prefix
                    :skip-count (count prefix-seq)
                    :last-iri last-k)
         ;; back up one because get-first-matching proceeded to first entry after
         (.prev iter)
         (lazy-cat [[last-k last-v]] (latest-versions-seq-iterator-based db iter)))))))

(comment
  #_(with-open [prefix-iter (rocksdb/raw-prefix-iter db (.getBytes iri-prefix))]
      (let [prefix-seq (into [] (rocksdb/rocks-entry-iterator-seq prefix-iter))
            last-entry (last prefix-seq)]
        (.close prefix-iter)
        (log/debug :iri-prefix iri-prefix
                   :parsed-iri parsed
                   :skip-count (count prefix-seq))
        (let [[last-k last-v] last-entry
              deserialized-last-entry [(String. last-k) last-v]]
          deserialized-last-entry))))

(comment
  "trying latest versions seq iter-based"
  (let [{:keys [iter out]}
        (->> genegraph.transform.clinvar.variation/variation-data-db
             (latest-versions-seq-iterator-based))]
    (with-open [iter iter]
      (doseq [entry (->> out (take 5))]
        (prn entry))))


  (let [{:keys [iter out]}
        (->> genegraph.transform.clinvar.variation/variation-data-db
             (latest-versions-seq-iterator-based))]
    (log/info :iter iter)
    (with-open [iter iter
                out-writer (io/writer "variations-test.txt")]
      (doseq [entry (->> out (take 100000))]
        (.write out-writer (prn-str (first entry)))))
    (.close iter)
    (genegraph.rocksdb/mem-stats genegraph.transform.clinvar.variation/variation-data-db))



  {"rocksdb.block-cache-usage" "13980177",
   "rocksdb.estimate-table-readers-mem" "624",
   "rocksdb.block-cache-pinned-usage" "8285064",
   "rocksdb.total-sst-files-size" "637748541",
   "rocksdb.num-live-versions" "1",
   "rocksdb.live-sst-files-size" "637748541"}

  {"rocksdb.block-cache-usage" "13980177",
   "rocksdb.estimate-table-readers-mem" "624",
   "rocksdb.block-cache-pinned-usage" "8285064",
   "rocksdb.total-sst-files-size" "637748541",
   "rocksdb.num-live-versions" "1",
   "rocksdb.live-sst-files-size" "637748541"}

  ())


(defn latest-records
  "For a RocksDB instance with keys and values structured in ways we know how to iterate,
   return the latest versions of non-deleted records.
   Keys must be parseable by parse-clinvar-resource-iri. Records are filtered out when the latest
   version contains .release_metadata{.deleted=true}."
  [db]

  #_#_iter (rocksdb/entire-db-iter db)
  #_#_seq (reify
            java.io.AutoCloseable
            (close [this] (;; TODO
                           )))
  (for [[entry-k entry-v] (->> (latest-versions-seq-iterator-based db)
                               (filter not-deleted?))]
    [entry-k entry-v]))

(defn snapshot-variation-db-rocksdb []
  (let [db genegraph.transform.clinvar.variation/variation-data-db
        out-fname "variation-data-db-snapshot-rocksdb.ndjson"]
    (with-open [writer (io/writer out-fname)]
      (doseq [[entry-k entry-v] (latest-records db)]
        (.write writer (json/generate-string entry-v))
        (.write writer "\n")))))

(defn snapshot-statements-db-rocksdb []
  (let [db ca/clinical-assertion-data-db
        out-fname "statements-data-db-snapshot.ndjson"]
    (with-open [writer (io/writer (io/file out-fname))]
      (doseq [[entry-k entry-v] (latest-records db)]
        (.write writer (json/generate-string entry-v))
        (.write writer "\n")))))

(comment
  (start-states!)

  (process-topic-file (cv-transform-test-fname "relative-cnv/cvraw-kinda-long-dup1.txt"))

  (process-topic-file "data/cg-vcep-2019-07-01/one_variant.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/variation.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/trait.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/trait_set.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/clinical_assertion.txt")
  (process-topic-file "data/cg-vcep-2019-07-01/one-scv.txt")

  #_(process-topic-file "cg-vcep-inputs/variation.txt")
  #_(process-topic-file "variation-inputs-556853.txt")
  ;; snapshot with Jena
  (snapshot-latest-variations)

  (write-latest-statement-snapshots :vrs/VariationGermlinePathogenicityStatement)
  (write-latest-statement-snapshots :vrs/ClinVarDrugResponseStatement)
  (write-latest-statement-snapshots :vrs/ClinVarOtherStatement)
  (write-latest-variation-snapshots)


  ;; Kyle testing
  (start-states!)
  (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/variation-556853.txt")
  (do (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/variation.txt")
      (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/trait.txt")
      (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/trait_set.txt")
      (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/clinical_assertion.txt"))

  (snapshot-statements-db-rocksdb))

(comment
  (def assertions
    (-> "cg-vcep-2019-07-01/clinical_assertion.txt"
        io/reader
        line-seq
        (->>
         (map #(json/parse-string % true))
         (filter #(= "SCV000852165" (get-in % [:content :id])))
         (map message-proccess-with-rocksdb!)
         (map docstore/store-document-raw-key)
         (map #(docstore/get-document-raw-key ca/clinical-assertion-data-db (:genegraph.annotate/data-id %)))
         #_(map :genegraph.annotate/data)))))

(comment
  "Testing delete operation"
  (start-states!)
  (rocksdb/rocks-destroy-state! #'genegraph.transform.clinvar.variation/variation-data-db)
  (process-topic-file (cv-transform-test-fname "one-variation-create-update-delete"
                                               "clinvar-raw-variation-36823-deleted.txt"))
  ;; last event in that file is a delete of the same id in prior events, so no data should remain
  (let [db genegraph.transform.clinvar.variation/variation-data-db]
    (assert (= 0 (count (take 100 (rocksdb/entire-db-entry-seq db)))))))

(comment
  (-> "statements.txt"
      io/reader
      line-seq
      (->> (map #(json/parse-string % true))
           (map map-compact-namespaced-values)
           ((fn [records]
              (with-open [writer (io/writer "statements-compacted.txt")]
                (doseq [rec records]
                  (.write writer (json/generate-string rec))
                  (.write writer "\n"))))))))

(defn run-full-topic-file []
  (let [input-filename "clinvar-raw.gz"
        messages (map #(json/parse-string % true) (line-seq (gzip-file-reader input-filename)))]
    (map message-proccess-with-jena! messages)))
