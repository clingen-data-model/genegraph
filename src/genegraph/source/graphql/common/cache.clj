(ns genegraph.source.graphql.common.cache
  (:require [clojure.core.cache :as cache]
            [io.pedestal.log :as log]
            [genegraph.env :as env]))

(defn create-cache []
  (cache/lru-cache-factory {} :threshold 100000))

(def resolver-cache (atom (create-cache)))

(defmacro defresolver
  "Define a Lacinia GraphQL resolver that uses the cache defined above. Resolver should be
  a two-argument function, the first argument representing the args to the resolver, the
  second representing value. Context is not used, or useable."
  [resolver-name args & body]
  (let [key (conj args resolver-name)
        fn-args (into [] (cons (gensym) args))]
    `(defn ~resolver-name ~fn-args
       (if env/use-gql-cache
         (do (swap! resolver-cache #(if (cache/has? % ~key)
                                      (cache/hit % ~key)
                                      (cache/miss % ~key (do ~@body))))
             (cache/lookup (deref resolver-cache) ~key))
         (do ~@body)))))

(defn reset-cache! 
  "Blow away the entire cache."
  []
  (log/info :fn reset-cache! :msg "Resetting resolver cache")
  (reset! resolver-cache (create-cache)))

