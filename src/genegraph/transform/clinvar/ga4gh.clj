(ns genegraph.transform.clinvar.ga4gh
  (:require [cheshire.core :as json]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.string :as str]
            [genegraph.annotate :as ann]
            [genegraph.database.names :as names :refer [prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.database.util :refer [tx write-tx]]
            [genegraph.server]
            [genegraph.sink.event :as event]
            [genegraph.sink.document-store :as docstore]
            [genegraph.source.registry.rocks-registry :as rocks-registry]
            [genegraph.transform.clinvar.clinical-assertion :as ca]
            [genegraph.transform.clinvar.common
             :as common
             :refer [map-compact-namespaced-values
                     map-rdf-resource-values-to-str map-unnamespace-values]]
            [genegraph.transform.clinvar.core :refer [add-parsed-value]]
            [genegraph.transform.clinvar.iri :refer [ns-cg]]
            [genegraph.transform.clinvar.util :as util]
            [genegraph.transform.clinvar.variation :as variation]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.transform.types :as xform-types]
            [genegraph.util.fs :refer [gzip-file-reader]]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [genegraph.rocksdb :as rocksdb]
            [taoensso.nippy :as nippy]))

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

#_(defn message-proccess-parallel!
    "Takes a message value map. The :value of a KafkaRecord, parsed as json"
    [messages]
    (->> messages
         (map #(-> %
                   eventify
                   ann/add-metadata
                   ann/add-action))
         ((fn [msgs]
            (flatten
             (map (fn [batch]
                    (log/info :batch-size (count batch)
                              :first (first batch)
                              :last (last batch))

                    (pmap (fn [e]
                            (try
                              (xform-types/add-data e)
                              (catch Exception ex
                                (log/error :fn :message-process!
                                           :msg "Exception adding data to event"
                                           :event e
                                           :exception (prn-str ex))
                                e)))
                          batch))
               ;; Batch the input seq into
                  (partition-by #(vector [(get-in % [:release_date])
                                          (get-in % [:content :entity_type])
                                          (get-in % [:content :subclass_type])])
                                msgs)))))
         (map (fn [e] (if (:genegraph.annotate/data e) (xform-types/add-model e) e)))
         (map (fn [e] (if (:genegraph.database.query/model e) (event/add-to-db! e) e)))))

#_(defn process-topic-file-parallel [input-filename]
    (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
      (with-open [statement-writer (io/writer (str input-filename "-output-statements"))
                  variation-descriptor-writer (io/writer (str input-filename "-output-variation-descriptors"))
                  other-writer (io/writer (str input-filename "-output-other"))]
        (doseq [event (message-proccess-parallel! messages)]
          (let [clinvar-type (:genegraph.transform.clinvar/format event)
                writer (case clinvar-type
                         :clinical_assertion statement-writer
                         :variation variation-descriptor-writer
                         other-writer)]
            (.write writer (-> event
                               :genegraph.annotate/data-contextualized
                               (dissoc "@context")
                               (json/generate-string)))
            (.write writer "\n"))))))

(defn add-to-db! [event]
  (if (:genegraph.database.query/model event)
    (do
      (event/add-to-db! event))
    event))

(defn message-proccess!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ; needed for add-to-db! to work
      ann/add-action
      #_((fn [e] (try
                   (xform-types/add-data e)
                   (catch Exception ex
                     (log/error :fn :message-process!
                                :msg "Exception adding data to event"
                                :event e
                                :exception ex)
                     e))))
      #_((fn [e] (if (:genegraph.annotate/data e) (xform-types/add-model e) e)))
      ((fn [e] (xform-types/add-model e)))
      ((fn [e] (if (:genegraph.database.query/model e) (add-to-db! e) e)))))

(defn message-proccess-no-db!
  "Takes a message value map. The :value of a KafkaRecord, parsed as json"
  [message]
  (-> message
      eventify
      ann/add-metadata
      ; needed for add-to-db! to work
      ann/add-action
      ((fn [e] (try
                 (xform-types/add-data e)
                 (catch Exception ex
                   (log/error :fn :message-process!
                              :msg "Exception adding data to event"
                              :event e
                              :exception ex)
                   e))))
      ((fn [e] (if (:genegraph.annotate/data e) (xform-types/add-model e) e)))))

(defn message-process-add-to-jena!
  [event]
  (-> event
      ((fn [e] (if (:genegraph.database.query/model e)
                 (add-to-db! e)
                 e)))))

(defn clinvar-add-iri [event]
  ;; TODO doesn't work for types without ids
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        content (:content message)]
    (assoc event :genegraph.annotate/iri
           (str (ns-cg (:entity_type content))
                "_" (:id content)
                "." (:release_date message)))))

(defn add-data-no-transform
  [event]
  (-> (add-parsed-value event)
      ((fn [e] (assoc e :genegraph.annotate/data
                      (-> (:genegraph.transform.clinvar.core/parsed-value e)
                          (#(merge % (:content %)))
                          (dissoc :content)))))
      ((fn [e] (assoc e :genegraph.annotate/data-contextualized
                      (merge (:genegraph.annotate/data e)
                             {"@context" {"@vocab" (str (get prefix-ns-map "cgterms"))}}))))
      clinvar-add-iri))

(defn process-topic-file-parallel-no-output [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (write-tx
     (doseq [event (->> messages
                        #_(take 10)
                        (pmap message-proccess-no-db!)
                        (map message-process-add-to-jena!))]
       (let [clinvar-type (:genegraph.transform.clinvar/format event)])))))

(defn process-topic-file [input-filename]
  (let [messages (map #(json/parse-string % true) (line-seq (io/reader input-filename)))]
    (with-open [statement-writer (io/writer (str input-filename "-output-statements"))
                variation-descriptor-writer (io/writer (str input-filename "-output-variation-descriptors"))
                other-writer (io/writer (str input-filename "-output-other"))]
      (write-tx
       (doseq [event (->> (map message-proccess! messages)
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


(defn bmap [f coll]
  (let [batches (partition-all 10 coll)]
    (reduce concat
            (pmap (fn [b]
                    (map f b))
                  batches))))

(defn test-folds []
  (time
   (def a (r/fold 100
                  r/foldcat
                  (r/map (fn [i] (doseq [n (range (int 1e5))] (inc n)) i)
                         (range 10000)))))
  (time
   (def b (reduce conj []
                  (map (fn [i] (doseq [n (range (int 1e5))] (inc n)) i)
                       (range 10000))))))

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

(defn parse-vd-iri
  "http://dataexchange.clinicalgenome.org/terms/VariationDescriptor_436617.2019-07-01
   -> {:id 436617 :version 2019-07-01}"
  [iri]
  (let [iri (str iri)
        type-prefix "http://dataexchange.clinicalgenome.org/terms/VariationDescriptor_"]
    (assert (.startsWith iri type-prefix)
            {:msg "Failed assertion"
             :iri iri})
    (let [id-plus-version (subs iri (count type-prefix))]
      (if (.contains id-plus-version ".")
        (let [id (subs id-plus-version 0 (.indexOf id-plus-version "."))
              version (subs id-plus-version (+ 1 (.indexOf id-plus-version ".")))]
          {:id id
           :version version})
        {:id id-plus-version}))))

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


(defn snapshot-variation-db-rocksdb []
  (let [db genegraph.transform.clinvar.variation/variation-data-db
        variation-descriptor-iri-prefix (ns-cg "VariationDescriptor_")
        out-fname "variation-data-db-snapshot-rocksdb.ndjson"]
    (doseq [[entry-k entry-v] (take 5 (rocksdb/entire-db-entry-seq db))]
      (let [thawed-k (nippy/fast-thaw entry-k)
            {id :id version :version} (parse-vd-iri thawed-k)
            data (:genegraph.annotate/data entry-v)
            iri-prefix (str variation-descriptor-iri-prefix id)
            prefix-seq (rocksdb/raw-prefix-seq db iri-prefix)]
        (doseq [[pent-k pent-v] prefix-seq]
          (let [thawed-pent-k (nippy/fast-thaw pent-k)]
            (prn {:id id :pent thawed-pent-k})))))

    #_(with-open [writer (io/writer (io/file out-fname))]
        (doseq [[entry-k entry-v] (rocksdb/entire-db-entry-seq db)]

          (let [data (:genegraph.annotate/data entry-v)]
            (.write writer (json/generate-string data))
            (.write writer "\n"))))))


(comment
  (start-states!)

  (process-topic-file (cv-transform-test-fname "relative-cnv/cvraw-kinda-long-dup1.txt"))


  (process-topic-file "cg-vcep-2019-07-01/trait.txt")
  (process-topic-file "cg-vcep-2019-07-01/trait_set.txt")

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

  ;; snapshot with the variation add-data snapshot rocksdb
  (snapshot-variation-db)
  (snapshot-latest-variations)
  (snapshot-latest-statements)
  #_(snapshot-latest-rocks-statements-of-type :vrs/VariationGermlinePathogenicityStatement)

  (write-latest-statement-snapshots :vrs/VariationGermlinePathogenicityStatement)
  (write-latest-statement-snapshots :vrs/ClinVarDrugResponseStatement)
  (write-latest-statement-snapshots :vrs/ClinVarOtherStatement)
  (write-latest-variation-snapshots)


  ;; Kyle testing
  (start-states!)
  (process-topic-file "cg-vcep-2019-07-01/variation.txt")
  (process-topic-file-parallel-no-output "cg-vcep-2019-07-01/variation.txt")
  (process-topic-file "cg-vcep-2019-07-01/variation-556853.txt")
  (write-latest-variation-snapshots))

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


(comment
  (-> "trait-test-input.txt"
      io/reader
      line-seq
      first
      (json/parse-string true)
      eventify
      genegraph.transform.clinvar.core/add-parsed-value
      ca/add-data-for-trait
      (genegraph.transform.clinvar.core/add-model-from-contextualized-data)
      :genegraph.database.query/model))

(defn run-full-topic-file []
  (let [input-filename "clinvar-raw.gz"
        messages (map #(json/parse-string % true) (line-seq (gzip-file-reader input-filename)))]
    (map message-proccess! messages)))
