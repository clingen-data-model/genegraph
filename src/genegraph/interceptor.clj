(ns genegraph.interceptor
  (:require [io.pedestal.interceptor.chain :as ic]
            [clojure.spec.alpha :as spec]))

(defn interceptor-enter-def [name event-fn]
  "Returns an interceptor definition with name, that defines an :enter
   function that accepts an interceptor context, extracts the genegraph stream
   event from that context, calls event-fn on the event, and updates the 
   context with the result event for the next interceptor to process."
  {:name name
   :enter (fn [context] 
            (let [new-context (event-fn context)]
              (if (or (:exception new-context)
                      (::spec/invalid new-context))
                (ic/terminate new-context)
                new-context)))})

(defn interceptor-leave-def [name event-fn]
  "Returns an interceptor definition with name, that defines an :leave 
   function that accepts an interceptor context, extracts the genegraph stream
   event from that context, calls event-fn on the event, and updates the 
   context with the result event for the next interceptor to process."
  {:name name
   :leave (fn [context] (->> context event-fn))})

