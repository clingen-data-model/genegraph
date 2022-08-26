(ns genegraph.event-analyzer
  "Examine the effect of changes in the codebase on the
   way events are processed."
  (:require [genegraph.sink.event-recorder :as recorder]
            [genegraph.sink.event :as event]
            [genegraph.database.query :as q]
            [genegraph.transform.core :as transform]
            [clojure.data :as data]))

(defn add-new-model [event]
  (when-not (::new-model event)
    (assoc event ::new-model (transform/add-model (::q/model event)))))

(defn resource-type-counts [event]
  (->> (q/select "select ?type where { ?x a ?type }" {} (::q/model event))
       (map q/to-ref)
       frequencies))

;; Ought to refactor around a better selective interceptor 
(defn resource-type-diff [event]
  (data/diff (resource-type-counts event)
             (-> event transform/add-model resource-type-counts)))

(defn model-diff
  "Compare the changes between the original model in EVENT and the model
  created applying the current transformation."
  [event]
  (let [new-model (-> event transform/add-model ::q/model)]
    {:created (q/difference new-model (::q/model event))
     :deleted (q/difference (::q/model event) new-model)}))

(defn pp-model-diff
  "Prints the return value from model-diff in Turtle."
  [event]
  (let [result (model-diff event)]
    (println "created:")
    (-> result :created q/pp-model)
    (println "removed:")
    (-> result :deleted q/pp-model)))

(defn model-changed?
  "true if the model in each event is not identical relative
  to when the event is first processed"
  [event]
  (not
   (q/is-isomorphic?
    (::q/model event)
    (-> event transform/add-model ::q/model))))

(defn model-sizes
  "return the size of the existing model in triples, as
   well as the current transformation."
  [event]
  {:previous (.size (::q/model event))
   :current (-> event transform/add-model ::q/model .size)})

(defn statistics
  "Summary statistics of event processing over the topic."
  [topic]
  (->> (recorder/events-for-topic topic)
       (map #(select-keys % [:exception]))
       (map keys)
       flatten
       frequencies))


(comment
  (->> (event-recorder/events-for-topic :gene-validity-raw)
       (take-last 10)
       (pmap #(assoc % ::model-changed (event-analyzer/model-changed? %)))
       (filter ::model-changed)
       (map event-analyzer/resource-type-diff))
  )
