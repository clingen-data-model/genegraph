(ns genegraph.model.evidence-line
  (:require [genegraph.database.query :as q]
            [genegraph.model.common :as common]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]))


(def evidence-line
  {:name :EvidenceLine
   :graphql-type :object
   :description "An evidence line represents an independent and meaningful argument for or against a particular proposition, that is based on the interpretation of one or more pieces of information as evidence."
   :implements [:Resource]
   :fields {:evidence {:type '(list :Resource)
                       :description "Evidence used by this evidence line"
                       :args {:class {:type :Type}
                              :transitive {:type 'Boolean}}
                       :resolve common/evidence-items}
            :score {:type 'Float
                    :description "Numeric score of the statement. May be nil, used only when the applicable criteria calls for a numeric score in the assertion or critera assessment."
                    :path [:sepio/evidence-line-strength-score]}}})
