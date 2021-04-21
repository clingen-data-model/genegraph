(ns genegraph.source.graphql.assertion
  (:require [genegraph.database.query :as q :refer [ld-> ld1->]]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(defresolver subject [args value]
  (tag-with-type (ld1-> value [:sepio/has-subject])
                 :GenericResource))

(defresolver ^:expire-by-value subject-of [args value]
  (map #(tag-with-type % :GenericAssertion) (ld-> value [[:sepio/has-subject :<]])))

(defresolver predicate [args value]
  (tag-with-type (ld1-> value [:sepio/has-predicate])
                 :GenericResource))

(defresolver object [args value]
  (tag-with-type (ld1-> value [:sepio/has-subject])
                 :GenericResource))

(def recursive-evidence-lines
  (q/create-query
   "select ?evidence_line where {
    ?curation ( :sepio/has-evidence-line | :sepio/has-evidence-item ) + ?evidence_line .
    ?evidence_line ( a / :rdfs/sub-class-of * ) :sepio/EvidenceLine .
    ?evidence_line ( a / :rdfs/sub-class-of * ) ?class }"))

(defn as-sepio-class [sepiokw]
  (some->> sepiokw name (keyword "sepio") q/resource))

(defresolver evidence-lines [args value]
  (let [sepio-class (as-sepio-class (:class args))
        result (cond
                 (and (:recursive args) sepio-class) (recursive-evidence-lines {:curation value
                                                                                :class sepio-class})
                 (:recursive args) (recursive-evidence-lines {:curation value})
                 :else (:sepio/has-evidence-line value))]
    (map #(tag-with-type % :GenericEvidenceLine) result)))
