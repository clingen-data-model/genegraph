(ns genegraph.source.graphql.common.secure
  (:require [genegraph.database.query :as q]))

(defmacro def-role-controlled-resolver [resolver-name roles args & body]
  (let [context (gensym "context")]
    `(defn ~resolver-name ~args
       (let [context# (first ~args)]
         (do ~@body)))))
