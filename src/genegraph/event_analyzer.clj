(ns genegraph.event-analyzer
  "Examine the effect of changes in the codebase on the
   way events are processed."
  (:require [genegraph.sink.event-recorder :as recorder]
            [genegraph.sink.event :as event]
            [genegraph.database.query :as q]
            [genegraph.transform.core :as transform]
            [clojure.data :as data]))


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
    (-> result :created q/to-turtle println)
    (println "removed:")
    (-> result :deleted q/to-turtle println)))

(defn model-changed?
  "true if the model in each event is not identical relative
  to when the event is first processed"
  [event]
  (not
   (q/is-isomorphic?
    (::q/model event)
    (-> event transform/add-model ::q/model))))

(defn statistics
  "Summary statistics of event processing over the topic."
  [topic]
  (->> (recorder/events-for-topic topic)
       (map #(select-keys % [:exception]))
       (map keys)
       flatten
       frequencies))


