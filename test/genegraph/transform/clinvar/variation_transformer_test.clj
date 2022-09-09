(ns genegraph.transform.clinvar.variation-transformer-test
  (:require [cheshire.core :as json]
            [clojure.data :as data]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as s]
            [clojure.test :refer [use-fixtures]]
            [genegraph.util :refer [str->bytestream
                                    dissoc-ns]]
            [genegraph.database.load :as l]
            [genegraph.annotate :as ann]
            [genegraph.annotate.action :as action]
            [genegraph.annotate.serialization :as ser]
            [genegraph.database.query :as q]
            [genegraph.database.util :refer [write-tx]]
            [genegraph.sink.event :as event]
            [genegraph.sink.event-recorder :as event-recorder]
            [genegraph.transform.clinvar.core]
            [genegraph.transform.clinvar.variation-new :as variation]
            [genegraph.transform.types :as xform-types]
            [io.pedestal.log :as log]
            [mount.core]))

(defn start-states! []
  (mount.core/start
   #_#'genegraph.server/server
   #'genegraph.database.instance/db
   #'genegraph.database.property-store/property-store
   #'genegraph.transform.clinvar.cancervariants/vicc-db
   #'genegraph.sink.event-recorder/event-database))

;; (defn fixtures [f]
;;   (start-states!)
;;   (f))

;; (use-fixtures :once fixtures)

(defn eventify [input-map]
  ;; Mostly replicating
  ;; (map #(stream/consumer-record-to-clj % :clinvar-raw))
  {::ann/format :clinvar-raw
   :genegraph.sink.event/key nil
   :genegraph.sink.event/value (json/generate-string input-map)
   ;;::timestamp (.timestamp consumer-record)
   ::topic "clinvar-raw"
   ::partition 0
   ;;::offset (.offset consumer-record)
   })

(defn file-lazy-seq
  "Lazy line-seq over a whole file. "
  [file-name]
  (let [reader (io/reader file-name)
        batch-size 10]
    (letfn [(read-batch [sequence-of-lines]
              (let [batch (take batch-size sequence-of-lines)]
                #_(log/info :batch batch)
                (if (seq batch)
                  (lazy-cat batch (read-batch (nthrest sequence-of-lines batch-size)))
                  (do (.close reader) nil))))]
      (read-batch (line-seq reader)))))

(defn get-variant-messages []
  (-> "clinvar-raw-filtered-variation.txt"
      io/reader
      line-seq
      ;; file-lazy-seq
      (->> (map #(json/parse-string % true))
           (filter #(= "variation" (get-in % [:content :entity_type]))))))

(defn test-vrs-normalization1 []
  (let [events (->> (get-variant-messages)
                    (filter #(= "12610" (get-in % [:content :id])))
                    (take 1)
                    (map eventify)
                    (map genegraph.transform.clinvar.core/add-parsed-value)
                    (map #(xform-types/add-model %))
                    #_(map #(xform-types/add-model-jsonld %)))]
    events))

(defn test-event-process! [variant-message]
  (-> variant-message
      (eventify)
      ;; (genegraph.transform.clinvar.core/add-parsed-value)
      (#(ann/add-metadata %))
      (#(xform-types/add-model %))
      (#(ann/add-iri %))
      (action/add-action)
      (#(event/add-to-db! %))
      (#((:enter ser/add-graphql-params-interceptor) %))
      (#((:enter ser/add-graphql-serialization-interceptor) %))
      (#(xform-types/add-model-jsonld %))
      (#((:leave event-recorder/record-event-interceptor) %))))

(defn test-event-process-jsonld! [variant-message]
  (-> variant-message
      (eventify)
      (#(ann/add-metadata %))
      (#(xform-types/add-model %))
      (#(ann/add-iri %))
      (action/add-action)
      (#(event/add-to-db! %))))

(defn message-proccess-no-db! [variant-message]
  (-> variant-message
      (eventify)
      (#(ann/add-metadata %))
      (#(xform-types/add-model %))))

(defn -write-graphql-from-db []
  (start-states!)
  (with-open [;;reader (io/reader "clinvar-raw-filtered.txt")
              ]
    (let [file-name "cg-vcep-variations-speedup.txt"]
      (with-open [writer (io/writer (io/file file-name))]
        (write-tx
         (doseq [event (-> (io/reader "clinvar-raw-filtered.txt")
                           line-seq
                           (->>
                            #_(take 10)
                            (map #(json/parse-string % true))))]
           (let [processed-event (test-event-process! event)
                 get-graphql-json (fn [e] (json/generate-string
                                           (-> e
                                               :genegraph.annotate.serialization/graphql-serialization
                                               :data
                                               :variation_descriptor_query)))
                 get-jsonld-json (fn [e] (:genegraph.annotate/jsonld e))
                 graphql-json (-> processed-event get-graphql-json)]
             (if (= nil (-> processed-event
                            :genegraph.annotate.serialization/graphql-serialization
                            :data
                            :variation_descriptor_query))
               (log/error :msg "No graphql serialization found"
                          :graphql-params (:graphql-params processed-event)
                          :genegraph.annotate.serialization/graphql-serialization
                          (:genegraph.annotate.serialization/graphql-serialization processed-event)
                          :iri (:genegraph.annotate/iri processed-event))
               (do (log/info :msg "Writing %d bytes of JSON" :count (count graphql-json))
                   (.write writer (-> processed-event get-graphql-json))
                   (.write writer "\n"))))))))))


(defn replace-kvs
  "Replace key-value pairs in input-map by applying kv-mutate-fn"
  [input-map kv-mutate-fn]
  (letfn [(apply-to-kv [k v] (kv-mutate-fn k v))]
    (if (map? input-map)
      (into {} (map (fn [[k v]] (apply-to-kv k v))
                    input-map))
      input-map)))

(defn test-replace-kvs []
  (let [m {:a 1
           :b {:c 3}
           "_id" 4}]
    (letfn [(mutator [k v]
              (vector (if (= "_id" k) "id" k)
                      (cond (map? v) (replace-kvs v mutator)
                            (sequential? v) (map #(replace-kvs % mutator) v)
                            :else v)))]
      (let [m2 (replace-kvs m mutator)]
        (pprint m2)))))


(defn model-json-preprocess-for-output [^clojure.lang.PersistentHashMap j-map]
  (letfn [(delete-contexts [k v]
            (if (or (= "@context" k) (= (keyword "@context") k))
              nil
              (vector k
                      (cond (map? v) (replace-kvs v delete-contexts)
                            (sequential? v) (map #(replace-kvs % delete-contexts) v)
                            :else v))))]
    (-> j-map
        (replace-kvs delete-contexts))))

(defn -write-jsonlds-no-db []
  (let [file-name "cg-vcep-variations-jsonld.txt"]
    (with-open [writer (io/writer (io/file file-name))]
      (write-tx (doseq [msg (get-variant-messages)]
                  (let [event (message-proccess-no-db! msg)
                        j (:genegraph.transform.clinvar.variation-new/contextualized event)]
                    (when j
                      (let [js (-> j
                                   model-json-preprocess-for-output
                                   (json/generate-string))]
                        (log/info :msg "Writing %d bytes of JSON" :count (count js))
                        (.write writer js)
                        (.write writer "\n")))))))))

(defn seq-pairs
  ;; https://stackoverflow.com/a/58240453/2172133
  [values]
  (map #(vector % %2) values (rest values)))

(defn diff-records []
  (let [variation-filename "variation-142423.txt"]
    (with-open [reader (io/reader (io/file variation-filename))]
      (doseq [pair (-> reader
                       line-seq
                       (->> (map #(json/parse-string % true))
                            (map #(assoc-in % [:content :content]
                                            (json/parse-string (get-in % [:content :content])
                                                               true)))
                            seq-pairs))]
        (pprint (data/diff (first pair) (second pair)))))))

(defn printnames []
  (let [variation-filename "variation-142423.txt"]
    (with-open [reader (io/reader (io/file variation-filename))]
      (doseq [rec (-> reader
                      line-seq
                      (->> (map #(json/parse-string % true))
                           (map #(get-in % [:content :name]))))]
        (println rec)))))
