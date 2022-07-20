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

;; Technical debt adopted to meet a specific use case for
;; the website and Phil. Would like to transition the site away from
;; using this, will remove this if and when that is possible.
(defn- has-proband-score-cap [resource]
  (some #(= (q/resource :sepio/ProbandScoreCapEvidenceLine) %)
        (q/ld-> resource [[:sepio/has-evidence :<] :rdf/type])))

(defn- hide-nested-variant-evidence [resources]
  (remove has-proband-score-cap resources))

(defn evidence-items [_ args value]
  (let [result
        (cond
          (and (:transitive args) (:class args))
          (transitive-evidence {:statement value
                                :class (q/resource (:class args))})
          (:transitive args)
          (transitive-evidence {:statement value})
          :else (:sepio/has-evidence value))]
    (if (:hide_nested_variant_evidence args)
      (hide-nested-variant-evidence result)
      result)))
