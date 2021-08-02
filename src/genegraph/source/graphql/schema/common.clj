(ns genegraph.source.graphql.schema.common
  (:require [genegraph.database.query :as q]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(def transitive-evidence
  (q/create-query
   "select ?evidence where {
    ?statement ( :sepio/has-evidence-line | :sepio/has-evidence-item | :sepio/has-evidence | ( ^ :sepio/has-subject )  ) + ?evidence .
    ?evidence ( a / :rdfs/sub-class-of * ) ?class }"))

(def direct-evidence
  (q/create-query
   "select ?evidence where {
    ?statement :sepio/has-evidence ?evidence .
    ?evidence ( a / :rdfs/sub-class-of * ) ?class }"))

(defn evidence-items [_ args value]
  (cond
    (and (:transitive args) (:class args))
    (transitive-evidence {:statement value
                          :class (q/resource (:class args))})
    (:transitive args)
    (transitive-evidence {:statement value})
    :else (:sepio/has-evidence value)))
