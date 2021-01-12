(ns genegraph.source.graphql.common.secure
  (:require [genegraph.database.query :as q]
            [clojure.set :as set]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as]]))

(defmacro def-role-controlled-resolver [resolver-name roles args & body]
  `(defn ~resolver-name ~args
     (let [context# (first ~args)
           user-roles# (:genegraph.auth/roles context#)
           authorized-roles# (->> ~roles (map q/resource) (into #{}))
           user-is-authorized# (seq (set/intersection authorized-roles# user-roles#))]
       (if user-is-authorized#
         (do ~@body)
         (resolve-as nil {:message "user is unauthorized"})))))
