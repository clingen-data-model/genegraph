(ns genegraph.event-analyzer
  "Examine the effect of changes in the codebase on the
   way events are processed."
  (:require [genegraph.sink.event-recorder :as recorder]
            [genegraph.sink.event :as event]
            [genegraph.database.query :as q]))





(defn statistics
  "Summary statistics of event processing over the topic."
  [topic]
  (->> (recorder/events-for-topic topic)
       (map #(select-keys % [:exception]))
       (map keys)
       flatten
       frequencies))


