(ns genegraph.source.graphql.common.cache
  (:require [io.pedestal.log :as log]
            [genegraph.env :as env]
            [mount.core :refer [defstate]]
            [genegraph.rocksdb :as rocksdb :refer [rocks-get rocks-put! rocks-delete!]]))

;; (defonce resolver-cache-db (atom (rocksdb/open "graphql_resolver_cache")))

(defstate resolver-cache-db
  :start (rocksdb/open "graphql_resolver_cache")
  :stop (rocksdb/close resolver-cache-db))

(defn get-from-cache [k]
  (rocks-get resolver-cache-db k))

(defn store-in-cache! [k v]
  (rocks-put! resolver-cache-db k v))

(defmacro defresolver
  "Define a Lacinia GraphQL resolver that uses the cache defined above. Resolver should be
  a two-argument function, the first argument representing the args to the resolver, the
  second representing value. Context is not used, or useable."
  [resolver-name args & body]
  (let [fn-args (into [] (cons (gensym) args))]
    (if env/use-gql-cache
      `(defn ~resolver-name ~fn-args
         (log/debug :fn :defresolver :msg (str "in resolver " ~resolver-name))
         (let [cache-key# (conj ~args ~(str *ns* "." resolver-name))]
           (log/debug :fn :defresolver :msg (str "using cache, looking up " cache-key#))
           (if-let [result# (get-from-cache cache-key#)]
             (do (log/debug :fn :defresolver :msg (str "cache hit: " result#))
                 result#)
             (let [calculated-result# (do ~@body)]
               (log/debug :fn :defresolver :msg "cache miss")
               (store-in-cache! cache-key# calculated-result#)
               calculated-result#))))
      `(defn ~resolver-name ~fn-args
         (do ~@body)))))

;; (defmacro defresolver
;;   "Define a Lacinia GraphQL resolver that uses the cache defined above. Resolver should be
;;   a two-argument function, the first argument representing the args to the resolver, the
;;   second representing value. Context is not used, or useable."
;;   [resolver-name args & body]
;;   (let [fn-args (into [] (cons '_ args))]
;;     `(defn ~resolver-name ~fn-args
;;        (do ~@body))))

(defn reset-cache! 
  "Blow away the entire cache."
  []
  (log/debug :fn :reset-cache! :msg "kaboom?"))



