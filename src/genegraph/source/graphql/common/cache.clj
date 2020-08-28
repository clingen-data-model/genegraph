(ns genegraph.source.graphql.common.cache
  (:require [io.pedestal.log :as log]
            [genegraph.env :as env]
            [mount.core :refer [defstate]]
            [genegraph.rocksdb :as rocksdb :refer [rocks-get rocks-put! rocks-delete!]]))

(defstate resolver-cache-db
  :start (rocksdb/open "graphql_resolver_cache")
  :stop (rocksdb/close resolver-cache-db))

(defn get-from-cache [[_ resolver-value :as k] opts]
  (println "getting from cache: " k)
  (cond 
    (:expire-by-value opts) (rocksdb/rocks-get-multipart-key 
                             resolver-cache-db
                             [resolver-value k])
    (:expire-always opts) (rocksdb/rocks-get-multipart-key
                           resolver-cache-db
                           [::expire-always k])
    :else (rocks-get resolver-cache-db k)))

(defn store-in-cache! [[_ resolver-value :as k] v opts]
  (println "storing in cache: " k)
  (cond 
    (:expire-by-value opts) (rocksdb/rocks-put-multipart-key!
                             resolver-cache-db
                             [resolver-value k]
                             v)
    (:expire-always opts) (rocksdb/rocks-put-multipart-key!
                           resolver-cache-db
                           [::expire-always k]
                           v)
    :else (rocks-put! resolver-cache-db k v)))

(defmacro defresolver
  "Define a Lacinia GraphQL resolver that uses the resolver cache. Resolver should be
  a two-argument function, the first argument representing the args to the resolver, the
  second representing value. Context is not used, or useable.

  Options that may be set via metadata include:
  :expire-by-value -- expire cache when an event arrives relating to the value object
  :expire-always -- expire the cache whenever an event that updates the database arrives
  :never-cache -- never cache the result of the resolver"
  [resolver-name args & body]
  (let [fn-args (into [] (cons (gensym) args))
        opts (meta resolver-name)]
    `(defn ~resolver-name ~fn-args
       (if env/use-gql-cache
         (do (log/debug :fn :defresolver :msg (str "in resolver " ~resolver-name))
             (let [cache-key# (conj ~args ~(str *ns* "." resolver-name))]
               (log/debug :fn :defresolver :msg (str "using cache, looking up " cache-key#))
               (let [result# (get-from-cache cache-key# ~opts)]
                 (if (= ::rocksdb/miss result#)
                   (let [calculated-result# (do ~@body)]
                     (log/debug :fn :defresolver :msg "cache miss")
                     (store-in-cache! cache-key# calculated-result# ~opts)
                     calculated-result#)
                   (do (log/debug :fn :defresolver :msg (str "cache hit: " result#))
                       result#)))))
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



