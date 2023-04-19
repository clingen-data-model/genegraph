(ns genegraph.transform.clinvar.vrs-analysis
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.sink.event :as ev]
            [genegraph.source.snapshot.core :as snapshot]
            [clojure.string :as str]
            [io.pedestal.log :as log]
            [me.raynes.fs :as fs]
            [genegraph.transform.clinvar.ga4gh :as ga4gh]))


(def files (mapv #(.getPath %) (fs/list-dir "vrs_analysis")))

(defn clinvar-raw-ize
  [bigquery-json-record]
  {"release_date" (get bigquery-json-record "release_date")
   "event_type" "create"
   "content" (dissoc bigquery-json-record
                     "release_date"
                     "datarepo_row_id")})

(-> files first io/reader line-seq first json/parse-string clinvar-raw-ize)

(defn eventify [input-map]
  ;; Mostly replicating
  ;; (map #(stream/consumer-record-to-event % :clinvar-raw))
  ;; but without some fields that are not used here
  {:genegraph.annotate/format :clinvar-raw
   :genegraph.sink.event/key nil
   :genegraph.sink.event/value (json/generate-string input-map)
   :genegraph.sink.stream/topic "clinvar-raw"
   :genegraph.sink.stream/partition 0})

;; (defn start-states! []
;;   (mount/start
;;    #'genegraph.transform.clinvar.cancervariants/cache-db
;;    #'genegraph.transform.clinvar.variation/variation-data-db
;;    #'genegraph.sink.event-recorder/event-database
;;    #'genegraph.sink.document-store/db
;;    #'genegraph.transform.clinvar.variation/variation-data-db
;;    #'genegraph.transform.clinvar.clinical-assertion/trait-data-db
;;    #'genegraph.transform.clinvar.clinical-assertion/trait-set-data-db
;;    #'genegraph.transform.clinvar.clinical-assertion/clinical-assertion-data-db
;;    #'rocks-registry/db
;;    #'rocks-registry/server))

(defn process-file [filename]
  (with-open [reader (io/reader filename)
              #_#_error-log (io/writer "errors.txt")]
    (let [limit Long/MAX_VALUE
          line-count (atom 0)]
      (doseq [[i event] (->> (line-seq reader)
                             (take limit)
                             (map (fn [line]
                                    (-> line
                                        json/parse-string
                                        (assoc "entity_type" "variation")
                                        clinvar-raw-ize
                                        eventify)))
                             (pmap (fn [event]
                                     (->  event
                                          (assoc ::ev/interceptors snapshot/interceptor-chain)
                                          (snapshot/process-event-catch-exceptions))))
                             (map-indexed vector))]
        (log/debug :data (:genegraph.annotate/data event))
        (log/debug :id (get-in event [:genegraph.annotate/data :id])
                   :description (get-in event [:genegraph.annotate/data :description]))
        (swap! line-count inc)
        (when (not (:genegraph.annotate/data event))
          (log/error :msg "Data not added to event" :event event))
        (when (= 0 (rem i 1000))
          (log/info :progress i))))))

(defn -main []
  (snapshot/start-states!)
  (let [line-count (atom 0)]
    (doseq [fname (->> files
                       (take 1))]
      (log/info :fname fname)
      (process-file fname))))

(comment
  (def written-datasets (snapshot/snapshot-write snapshot/snapshot-datasets)))

(comment
  (require 'genegraph.transform.clinvar.variation
           '[mount.core :as mount])

  (def db-var #'genegraph.transform.clinvar.variation/variation-data-db)
  (mount/start db-var)
  (reduce (fn [agg val]
            (+ 1 agg))
          0
          (rocksdb/entire-db-seq (var-get db-var)))
  ())


(defn count-variation-types []
  (with-open [writer-allele (io/writer "variation-allele.txt")
              writer-rel-cnv (io/writer "variation-rel-cnv.txt")
              writer-abs-cnv (io/writer "variation-abs-cnv.txt")
              writer-text (io/writer "variation-text.txt")
              writer-null (io/writer "variation-null.txt")
              writer-other (io/writer "variation-other.txt")
              variation-writer (io/writer "variation.txt")
              error-writer (io/writer "variation-errors.txt")]
    (let [counters (atom {})
          db-var #'genegraph.transform.clinvar.variation/variation-data-db
          writers {"Allele" writer-allele
                   "RelativeCopyNumber" writer-rel-cnv
                   "AbsoluteCopyNumber" writer-abs-cnv
                   "Text" writer-text
                   nil writer-null}]
      (letfn [(count-canonical-variations [counters canonical-variation]
                (let [{core-variation :canonical_context} canonical-variation
                      {core-variation-type :type} core-variation]
                  (.write (get writers core-variation-type writer-other)
                          (str (json/generate-string canonical-variation) "\n"))
                  (swap! counters (fn [current-counters]
                                    (update current-counters
                                            core-variation-type
                                            #(+ 1 (or % 0)))))))
              (count-non-canonical [counters variation]
                (let [{variation-type :type} variation]
                  (.write (get writers variation-type writer-other)
                          (str (json/generate-string variation) "\n"))
                  (swap! counters (fn [current-counters]
                                    (update current-counters
                                            variation-type
                                            #(+ 1 (or % 0)))))))]
        (with-open [iter (rocksdb/entire-db-iter (var-get db-var))]
          (doseq [[idx [k descriptor]]
                  (map-indexed vector (->> (ga4gh/latest-versions-seq-all iter)
                                           #_(take 10000)))]
            (let [{canonical-variation :canonical_variation} descriptor]
              (case (:type canonical-variation)
                "CanonicalVariation" (count-canonical-variations counters canonical-variation)
                nil (do (log/error :msg "nil variation type"
                                   :descriptor descriptor)
                        (.write error-writer (str/trim (prn-str descriptor)))
                        (.write error-writer "\n")
                        (count-non-canonical counters {:type nil}))
                (count-non-canonical counters canonical-variation))
              (.write variation-writer (json/generate-string descriptor))
              (.write variation-writer "\n"))
            (when (= 0 (rem idx 1000))
              (log/info :counters @counters)))))
      (log/info :counters @counters)
      counters)))
