(ns genegraph.source.graphql.common.cache
  (:require [clojure.core.cache :as cache]))

(def resolver-cache (atom (cache/lru-cache-factory {})))

(defmacro defresolver
  "Define a Lacinia GraphQL resolver that uses the cache defined above. Resolver should be
  a two-argument function, the first argument representing the args to the resolver, the
  second representing value. Context is not used, or useable."
  [resolver-name args & body]
  (let [key (conj args resolver-name)
        fn-args (into [] (cons (gensym) args))]
    `(defn ~resolver-name ~fn-args
       (swap! resolver-cache #(if (cache/has? % ~key)
                                (do (println "cache hit!") (cache/hit % ~key))
                                (do (println "cache miss!") (cache/miss % ~key (do ~@body)))))
       (cache/lookup @resolver-cache ~key))))

(defn reset-cache! 
  "Blow away the entire cache."
  []
  (reset! resolver-cache (atom (cache/lru-cache-factory {}))))

(defresolver test-resolver [args value]
  (println "args: " args " value: " value)
  42)

(defresolver test-resolver2 [args value]
  (println "args: " args " value: " value)
  "llama")
