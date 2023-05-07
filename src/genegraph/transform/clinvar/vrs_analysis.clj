(ns genegraph.transform.clinvar.vrs-analysis
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.climate.claypoole :as cp]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.sink.event :as ev]
            [genegraph.source.snapshot.core :as snapshot]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [genegraph.transform.clinvar.ga4gh :as ga4gh]
            [io.pedestal.log :as log]
            [me.raynes.fs :as fs]))


#_(def files (mapv #(.getPath %) (fs/list-dir "vrs_analysis")))

;; "Elapsed time: 2464946.621451 msecs"
(def files (mapv #(.getPath %) (fs/list-dir "vrs_analysis_v2")))

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
              #_#_error-log (io/writer "errors.txt")
              data-writer (io/writer "data.ndedn")]
    (cp/with-shutdown! [threadpool (cp/threadpool 10)]
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
                               (cp/pmap threadpool
                                        (fn [event]
                                          (->  event
                                               (assoc ::ev/interceptors snapshot/interceptor-chain)
                                               (snapshot/process-event-catch-exceptions))))
                               (map-indexed vector))]
          (log/debug :data (:genegraph.annotate/data event))
          (log/debug :id (get-in event [:genegraph.annotate/data :id])
                     :description (get-in event [:genegraph.annotate/data :description]))
          (swap! line-count inc)
          (if (not (:genegraph.annotate/data event))
            (log/error :msg "Data not added to event" :event (dissoc event :exceptions))
            (.write data-writer (prn-str (:genegraph.annotate/data event))))

          (when (= 0 (rem i 1000))
            (log/info :progress i)))
        (log/info :line-count @line-count)))))

(defn -main []
  (snapshot/start-states!)
  (doseq [fname (->> files)]
    (log/info :fname fname)
    (process-file fname)))

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

(def thread-pool (cp/threadpool 50))

(defn test-long
  "
   with daphne on server: 4053.801761 msecs
   with gunicorn with 4 workers: 4142.269335 msecs (warning)
   with uvicorn directly: 3735.313562 msecs

   gunicorn warnings:
   [2023-05-03 22:20:45 +0000] [266] [WARNING] Unsupported upgrade request.
[2023-05-03 22:20:45 +0000] [266] [WARNING] No supported WebSocket library detected. Please use \"pip install 'uvicorn[standard]'\", or install 'websockets' or 'wsproto' manually.

   still hangs: NC_000021.8:g.14714507_29216662dup
   NC_000011.9:g.104288964_134937416dup
   NC_000015.9:g.31115047_102354857dup
   NC_000020.10:g.17705775_31600738dup
   "
  []
  (let [exprs (repeat 100 "NC_000001.10:g.(?_147230250)_(147231366_?)dup")]
    (->> exprs
         (map (fn [exp]
                {:hgvs {:expr exp
                        :copy-class "Duplication"}}))
         (cp/pmap
          thread-pool
          genegraph.transform.clinvar.cancervariants/normalize-relative-copy-number)
         #_(map genegraph.transform.clinvar.cancervariants/normalize-relative-copy-number)
         doall)
    #_(->> exprs
           (map (fn [exp]
                  {:hgvs {:expr exp
                          :copy-class "Duplication"}}))
           (map genegraph.transform.clinvar.cancervariants/normalize-relative-copy-number)
           (cp/pmap pool identity)
           doall)))

(comment
  {:counters {"CopyNumberChange" 23901, "Text" 6933, "Allele" 7, nil 2}, :line 192})

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

(with-open [reader (io/reader "vrs_analysis_v2/000000000000")]
  (->> (line-seq reader)
       (map #(json/parse-string % true))))

(comment
  "problem variant:"
  '{:fn :normalize-relative-copy-number,
    :expr "NC_000017.11:g.43070192_43078360dup",
    :copy-class "Duplication",

    :efo-copy-class "efo:0030070", :line 144}

  (genegraph.transform.clinvar.cancervariants/normalize-relative-copy-number
   {:hgvs {:expr "NC_000017.11:g.43070192_43078360dup"
           :copy-class "Duplication"}})

  (last
   (cp/pmap thread-pool
            (fn [_]
              (genegraph.transform.clinvar.cancervariants/normalize-relative-copy-number
               {:hgvs {:expr "NC_000017.11:g.43070192_43078360dup"
                       :copy-class "Duplication"}}))
            (range 1000)))


  ())
