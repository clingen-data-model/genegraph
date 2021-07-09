(ns genegraph.model.assertion
  (:require [genegraph.database.query :as q]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))

(def transitive-evidence-items
  (q/create-query
   "select ?evidence_line where {
    ?assertion ( :sepio/has-evidence-line | :sepio/has-evidence-item | :sepio/has-evidence | ( ^ :sepio/has-subject )  ) + ?evidence_line .
    ?evidence_line ( a / :rdfs/sub-class-of * ) ?class }"))

(defn evidence-items [_ args value]
  (cond
    (and (:transitive args) (:class args))
    (transitive-evidence-items {:assertion value
                                :class (:class args)})
    (:transitive args)
    (transitive-evidence-items {:assertion value})
    :else (:sepio/has-evidence-line value)))

(def assertion
  {:name :Assertion
   :graphql-type :object
   :description "An assertion or proposition in the SEPIO model."
   :implements [:Resource]
   :fields {:subject {:type :Resource
                      :description "The subject of this assertion"
                      :path [:sepio/has-subject]}
            :predicate {:type :Resource
                        :description "The predicate of this assertion"
                        :path [:sepio/has-predicate]}
            :object {:type :Resource
                     :description "The object of this assertion"
                     :path [:sepio/has-object]}
            :qualifier {:type '(list :Resource)
                        :description "Additional elements limiting the scope of the assertion"
                        :path [:sepio/has-qualifier]}
            :evidence {:type '(list :Resource)
                       :description "Evidence used in in support of the assertion"
                       :args {:class {:type :Type}
                              :transitive {:type 'Boolean}}
                       :resolve evidence-items}}})