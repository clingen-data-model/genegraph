(ns genegraph.util.test-data
  "Generate sample data using a slice of production data. 
  Will serve as the basis for unit testing. While the data generated from this should work
  from an empty Jena DB (for testing, at least), these functions require a running and competent
  Jena instance to work."
  (:require [genegraph.sink.stream :as stream]
            [genegraph.annotate :as ann]
            [genegraph.database.query :as q]))

(def streams-to-sample
  [:gene-dosage-stage
   :actionability
   :gene-validity])

(defn annotate-stream [stream]
  (->> stream
       (map ann/add-metadata)
       (map ann/add-model)
       (map ann/add-iri)
       (map ann/add-validation)       
       (map ann/add-subjects)))

(defn stream-data [stream]
  (-> (stream/topic-data stream)
      annotate-stream
      top-curations-with-updates))

(defn top-curations-with-updates [stream]
  (->> stream (group-by ::ann/iri) (sort-by #(count (second %))) (take 1)))



