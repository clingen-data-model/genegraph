(ns genegraph.transform.clinvar.ga4gh
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.core.reducers :as r]
            [genegraph.annotate :as ann]
            [genegraph.database.names :refer [prefix-ns-map]]
            [genegraph.database.query :as q]
            [genegraph.database.util :refer [tx write-tx]]
            [genegraph.sink.event :as event]
            [genegraph.transform.clinvar.clinical-assertion :as ca]
            [genegraph.transform.clinvar.variation :as variation]
            [genegraph.transform.clinvar.common :as common :refer [replace-kvs]]
            [genegraph.transform.clinvar.core :refer [add-parsed-value]]
            [genegraph.transform.clinvar.iri :refer [ns-cg]]
            [genegraph.transform.jsonld.common :as jsonld]
            [genegraph.transform.types :as xform-types]
            [genegraph.util.fs :refer [gzip-file-reader]]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [genegraph.database.names :as names]
            [genegraph.server]))

(def stop-removing-unused [#'write-tx #'tx #'pprint])

(defn start-states! []
  (mount/start
   #'genegraph.database.instance/db
   #'genegraph.database.property-store/property-store
   #'genegraph.transform.clinvar.cancervariants/vicc-db
   #'genegraph.sink.event-recorder/event-database))

(defn eventify [input-map]
  ;; Mostly replicating
  ;; (map #(stream/consumer-record-to-clj % :clinvar-raw))
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

(defn message-proccess!
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
      ((fn [e] (if (:genegraph.annotate/data e) (xform-types/add-model e) e)))
      ((fn [e] (if (:genegraph.database.query/model e) (event/add-to-db! e) e)))))

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

(defn add-model [event]
  (if (:genegraph.annotate/data event)
    (xform-types/add-model event)
    event))

(defn add-to-db! [event]
  (if (:genegraph.database.query/model event)
    (event/add-to-db! event)
    event))

(defn add-jsonld [event]
  (assoc event
         :genegraph.annotate/jsonld
         (jsonld/model-to-jsonld (:genegraph.database.query/model event))))

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

(defn map-rdf-resource-values-to-str
  [input-map]
  (letfn [(mutator [k v]
            (if (= genegraph.database.query.types.RDFResource (class v))
              [k (str v)]
              [k v]))]
    (replace-kvs input-map mutator)))

(defn ^String un-namespace-term
  "Takes a potentially expanded namespaced term, and returns the term without the namespace.
   e.g. http://example.org/MyTerm -> MyTerm.
   Uses namespaces from namespaces.edn.

   TODO only some of these are in use in this module. It performs pretty well
   but if we could check just some namespaces, might be faster."
  [term]
  (let [term (str term)
        namespaces (keys names/ns-prefix-map)]
    (or (some #(when (.startsWith term %)
                 (subs term (.length %)))
              namespaces)
        term)))

(defn ^String un-prefix-term
  [term]
  (let [term (str term)
        prefixes (map #(str % ":") (keys names/prefix-ns-map))]
    (or (some #(when (.startsWith term %)
                 (subs term (.length %)))
              prefixes)
        term)))

(defn map-unnamespace-values
  [input-map]
  (let [fields-to-process (set [:type])]
    (letfn [(mutator [k v]
              (if (and (contains? fields-to-process k)
                       (string? v))
                [k (un-namespace-term v)]
                [k v]))]
      (replace-kvs input-map mutator))))

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

(defn snapshot-latest-statements []
  (try
    (let [output-filename "statements.txt"]
      (with-open [writer (io/writer output-filename)]
      ;; TODO look at group by for this.
      ;; Just want each iri for latest release_date for each is-version-of
        (tx
         (let [unversioned-resources
               (q/select (str "select distinct ?vof where { "
                              "?i a :vrs/VariationGermlinePathogenicityStatement . "
                              "?i :vrs/record-metadata ?rmd . "
                              "?rmd :dc/is-version-of ?vof . "
                              "} "))
               _ (log/info :fn :snapshot-latest-statements
                           :msg (str "unversioned-resources count: "
                                     (count unversioned-resources)))
               latest-versioned-resources
               (map (fn [vof]
                      (let [rs (q/select (str "select ?i where { "
                                              "?i a :vrs/VariationGermlinePathogenicityStatement . "
                                              "?i :vrs/record-metadata ?rmd . "
                                              "?rmd :dc/is-version-of ?vof . "
                                              "?rmd :owl/version-info ?release_date . "
                                              "} "
                                              "order by desc(?release_date) "
                                              "limit 1")
                                         {:vof vof})]
                        (if (< 1 (count rs))
                          (log/error :msg "More than 1 statement returned"
                                     :vof vof :rs rs)
                          (first rs))))
                    (->> unversioned-resources
                         #_(take 10)))]
           (doseq [statement-resource (->> latest-versioned-resources)]
             (try
               (let [statement-output (ca/clinical-assertion-resource-for-output statement-resource)
                     post-processed-output (-> statement-output
                                               map-rdf-resource-values-to-str
                                               common/map-remove-nil-values
                                               map-unnamespace-values)]

                 (log/info :post-processed-output post-processed-output)

                 (.write writer (json/generate-string post-processed-output))
                 (.write writer "\n"))
               (catch Exception e
                 (print-stack-trace e)
                 (log/error :msg "Failed to output statement"
                            :statement-resource statement-resource))))))))
    (catch Exception e
      (print-stack-trace e))))

(defn snapshot-latest-statements-drugresponse []
  (try
    (let [output-filename "statements-drugresponse.txt"]
      (with-open [writer (io/writer output-filename)]
      ;; TODO look at group by for this.
      ;; Just want each iri for latest release_date for each is-version-of
        (tx
         (let [unversioned-resources
               (q/select (str "select distinct ?vof where { "
                              "?i a :vrs/ClinVarDrugResponseStatement . "
                              "?i :vrs/record-metadata ?rmd . "
                              "?rmd :dc/is-version-of ?vof . "
                              "} "))
               _ (log/info :fn :snapshot-latest-statements
                           :msg (str "unversioned-resources count: "
                                     (count unversioned-resources)))
               latest-versioned-resources
               (map (fn [vof]
                      (let [rs (q/select (str "select ?i where { "
                                              "?i a :vrs/ClinVarDrugResponseStatement . "
                                              "?i :vrs/record-metadata ?rmd . "
                                              "?rmd :dc/is-version-of ?vof . "
                                              "?rmd :owl/version-info ?release_date . "
                                              "} "
                                              "order by desc(?release_date) "
                                              "limit 1")
                                         {:vof vof})]
                        (if (< 1 (count rs))
                          (log/error :msg "More than 1 statement returned"
                                     :vof vof :rs rs)
                          (first rs))))
                    (->> unversioned-resources
                         (take 10)))]
           (doseq [statement-resource (->> latest-versioned-resources)]
             (try
               (let [statement-output (ca/clinical-assertion-resource-for-output statement-resource)
                     post-processed-output (-> statement-output
                                               map-rdf-resource-values-to-str
                                               common/map-remove-nil-values
                                               map-unnamespace-values)]

                 (log/info :post-processed-output post-processed-output)

                 (.write writer (json/generate-string post-processed-output))
                 (.write writer "\n"))
               (catch Exception e
                 (print-stack-trace e)
                 (log/error :msg "Failed to output statement"
                            :statement-resource statement-resource))))))))
    (catch Exception e
      (print-stack-trace e))))

(defn snapshot-latest-statements-other []
  (try
    (let [output-filename "statements-other.txt"]
      (with-open [writer (io/writer output-filename)]
      ;; TODO look at group by for this.
      ;; Just want each iri for latest release_date for each is-version-of
        (tx
         (let [unversioned-resources
               (q/select (str "select distinct ?vof where { "
                              "?i a :vrs/ClinVarOtherStatement . "
                              "?i :vrs/record-metadata ?rmd . "
                              "?rmd :dc/is-version-of ?vof . "
                              "} "))
               _ (log/info :fn :snapshot-latest-statements
                           :msg (str "unversioned-resources count: "
                                     (count unversioned-resources)))
               latest-versioned-resources
               (map (fn [vof]
                      (let [rs (q/select (str "select ?i where { "
                                              "?i a ?type . "
                                              "?i :vrs/record-metadata ?rmd . "
                                              "?rmd :dc/is-version-of ?vof . "
                                              "?rmd :owl/version-info ?release_date . "
                                              "} "
                                              "order by desc(?release_date) "
                                              "limit 1")
                                         {:vof vof
                                          :type :vrs/ClinVarOtherStatement})]
                        (if (< 1 (count rs))
                          (log/error :msg "More than 1 statement returned"
                                     :vof vof :rs rs)
                          (first rs))))
                    (->> unversioned-resources
                         (take 10)))]
           (doseq [statement-resource (->> latest-versioned-resources)]
             (try
               (let [statement-output (ca/clinical-assertion-resource-for-output statement-resource)
                     post-processed-output (-> statement-output
                                               map-rdf-resource-values-to-str
                                               common/map-remove-nil-values
                                               map-unnamespace-values)]

                 (log/info :post-processed-output post-processed-output)

                 (.write writer (json/generate-string post-processed-output))
                 (.write writer "\n"))
               (catch Exception e
                 (print-stack-trace e)
                 (log/error :msg "Failed to output statement"
                            :statement-resource statement-resource))))))))
    (catch Exception e
      (print-stack-trace e))))


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
               (map (fn [vof]
                      (let [rs (q/select (str "select ?i where { "
                                              "?i a :vrs/CanonicalVariationDescriptor . "
                                              "?i :vrs/record-metadata ?rmd . "
                                              "?rmd :dc/is-version-of ?vof . "
                                              "?rmd :owl/version-info ?release_date . "
                                              "} "
                                              "order by desc(?release_date) "
                                              "limit 1")
                                         {:vof vof})]
                        (if (< 1 (count rs))
                          (log/error :msg "More than 1 statement returned"
                                     :vof vof :rs rs)
                          (first rs))))
                    (->> unversioned-resources
                         #_(take 1)))]
           (doseq [descriptor-resource (->> latest-versioned-resources)]
             (try
               (let [descriptor-output (variation/variation-descriptor-resource-for-output descriptor-resource)
                     #_#__ (log/info :descriptor-output descriptor-output)
                     post-processed-output (-> descriptor-output
                                               map-rdf-resource-values-to-str
                                               common/map-remove-nil-values
                                               map-unnamespace-values)]

                 #_(log/info :post-processed-output post-processed-output)
                 (.write writer (json/generate-string post-processed-output))
                 (.write writer "\n"))
               (catch Exception e
                 (print-stack-trace e)
                 (log/error :msg "Failed to output variation descriptor"
                            :descriptor-resource descriptor-resource))))))))
    (catch Exception e
      (print-stack-trace e))))


(comment
  (start-states!)
  (process-topic-file "cg-vcep-2019-07-01/variation.txt")
  (snapshot-latest-variations))


(defn message-process-no-transform!
  [message]
  (-> message
      eventify
      ann/add-metadata
      ann/add-action
      add-data-no-transform
      add-model
      #_add-jsonld
      add-to-db!))

(defn ingest-file-no-transform [input-filename]
  (let [messages (-> input-filename
                     io/reader
                     line-seq
                     (->> #_(take 10)
                      (map #(json/parse-string % true))))]
    (doseq [event (map message-process-no-transform! messages)]
      (log/info :fn :ingest-file-no-transform
                ;;:event event
                :iri (:genegraph.annotate/iri event)
                #_#_:model (:genegraph.database.query/model event)))))


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
