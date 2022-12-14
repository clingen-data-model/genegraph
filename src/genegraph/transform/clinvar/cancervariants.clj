(ns genegraph.transform.clinvar.cancervariants
  (:require [cheshire.core :as json]
            [genegraph.database.names :as names :refer [prefix-ns-map]]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.source.registry.redis :as redis]
            [clj-http.client :as http-client]
            #_[genegraph.util.http-client :as ghttp :refer [http-get-with-cache]]
            [io.pedestal.log :as log]
            [mount.core :as mount])
  (:import (clojure.lang Keyword)
           (org.rocksdb RocksDB)))

(def variation-base-url-prod
  "https://normalize.cancervariants.org/variation/")

(def variation-base-url-dev
  #_"http://variation-normalization-dev.us-east-2.elasticbeanstalk.com/variation/"
  "http://variation-normalization-dev-eb.us-east-2.elasticbeanstalk.com/variation")

(def variation-base-url variation-base-url-dev)

(def url-to-canonical
  "URL for cancervariants.org VRS normalization.
  Returns a JSON document containing a CanonicalVariation under the canonical_variation field, along with other metadata."
  (str variation-base-url "/to_canonical_variation"))

(def url-absolute-cnv
  "URL for cancervariants.org Absolute Copy Number normalization."
  (str variation-base-url "/parsed_to_abs_cnv"))

(def vicc-context
  {"id" {"@id" "@id"},
   "_id" {"@id" "@id"},
   "type" {"@id" "@type"
           "@type" "@id"}
   "@vocab" (get prefix-ns-map "vrs") ;"https://vrs.ga4gh.org/"
   "normalize.variation" {"@id" "https://github.com/cancervariants/variation-normalization/"
                          "@prefix" true}})

;; TODO move this after vrs-variation-for-expression
;; Allow passing in an existing context, to which the VICC context will be 'smart' merged to avoid collisions
(defn add-vicc-context [val]
  (assoc val "@context" vicc-context))

;; vicc-db-name is to store the http cache, which caches the full url, params, etc,
;; and most of the response object.
;; but really all we need is the expression -> variation object cached.
;; each normalization function may use a slightly different expr specification, so each
;; should define their own key fn for how the expr is deterministically serialized.
(def vicc-db-name "cancervariants-cache.db")
(def vicc-expr-db-name "cancervariants-expr-cache.db")

(mount/defstate ^RocksDB vicc-db
  :start (rocksdb/open vicc-db-name)
  :stop (rocksdb/close vicc-db))

(mount/defstate ^RocksDB vicc-expr-db
  :start (rocksdb/open vicc-expr-db-name)
  :stop (rocksdb/close vicc-expr-db))

#_(def normalize-canonical-cache
    "Maps two keys :get and :put to get and put cached values for normalize-canonical function calls"
    (letfn [(key-fn [^String variation-expression ^Keyword expression-type]
              (-> {:variation-expression variation-expression :expression-type expression-type}
                  seq (->> (into [])) str))]
      {:put (fn [^String variation-expression ^Keyword expression-type value]
              (rocksdb/rocks-put! vicc-expr-db
                                  (key-fn variation-expression expression-type)
                                  value))
       :get (fn [^String variation-expression ^Keyword expression-type value]
              (rocksdb/rocks-get vicc-expr-db (key-fn variation-expression expression-type)))}))

(defn normalize-canonical
  "Normalizes an :hgvs or :spdi expression.
   Throws exception if the request fails. If the normalization
   fails but the request succeeds, should return Text variation type."
  [^String variation-expression ^Keyword expression-type]
  (log/debug :fn :normalize-canonical
             :variation-expression variation-expression
             :expression-type expression-type)
  (let [response (http-client/get url-to-canonical
                                  {:throw-exceptions false
                                   :query-params {"q" variation-expression
                                                  "fmt" (name expression-type)
                                                  "untranslatable_returns_text" true}})
        status (:status response)]
    (case status
      200 (let [body (-> response :body json/parse-string)]
            (log/debug :fn :normalize-canonical :body body)
            (-> body (get "canonical_variation") add-vicc-context))
      ;; Error case
      (throw (ex-info "Error in VRS normalization request"
                      {:fn :normalize-canonical
                       :status status
                       :response response})))))

(defn normalize-absolute-copy-number
  "Normalizes an absolute copy number map of
   assembly, chr, start, end, total_copies.
   Throws exception on error."
  [input-map]
  (log/debug :fn :normalize-absolute-copy-number :input-map input-map)
  (let [response (http-client/get url-absolute-cnv
                                  {:throw-exceptions false
                                   :query-params
                                   (into {"untranslatable_returns_text" true}
                                         (map #(vector (-> % first name) (-> % second))
                                              (select-keys input-map [:assembly
                                                                      :chr
                                                                      :start
                                                                      :end
                                                                      :total_copies])))})
        status (:status response)]
    (case status
      200 (let [body (-> response :body json/parse-string)]
            (log/debug :fn :normalize-absolute-copy-number :body body)
            (assert not-empty (-> body (get "absolute_copy_number")))
            (-> body (get "absolute_copy_number") add-vicc-context))
      ;; Error case
      (throw (ex-info "Error in VRS normalization request"
                      {:fn :normalize-absolute-copy-number
                       :status status
                       :response response
                       :input-map input-map})))))

(def redis-opts
  "Pool opts:
   https://github.com/ptaoussanis/carmine/blob/e4835506829ef7fe0af68af39caef637e2008806/src/taoensso/carmine/connections.clj#L146
   Can pass a predefined pool and shut this down when finished with it
   https://github.com/ptaoussanis/carmine/commit/a1d0c4ec1dd4848a9323eaa149ab284509664515
   Note: nil .spec values defaults to 127.0.0.1:6379"
  {:pool-fn #(redis/make-connection-pool {#_(comment Default pool options)})
   :spec {:uri (System/getenv "CACHE_REDIS_URI")}})

(mount/defstate redis-db
  :start (if (redis/connectable? redis-opts)
           (assoc redis-opts :pool ((:pool-fn redis-opts)))
           (throw (ex-info "Could not connect to redis"
                           {:conn-opts redis-opts})))
  ;; TODO test that this .close actually terminates the connections that are idle
  ;; Look at threads
  ;; https://stackoverflow.com/a/3018672/2172133
  ;; Potentially tweak pool options:
  #_{:time-between-eviction-runs-ms 1000
     :min-evictable-idle-time-ms 1000
     :test-on-borrow? true
     :test-on-return? true
     :test-while-idle? true}
  :stop (.close (:pool redis-db)))


(defn expression-key-serializer
  "TODO variation-expression can be a map if its a CNV.
   Need to explicitly order those map entries?"
  [variation-expression expression-type]
  (doto (-> [variation-expression
             expression-type]
            prn-str)
    (#(log/debug :fn :expression-key-serializer
                 :variation-expression variation-expression
                 :expression-type expression-type
                 :result %))))

(defn redis-expression-cache-put
  "Put the value in the cache keyed on the expression and its type."
  [variation-expression expression-type value]
  (log/debug :fn :redis-expression-cache-put
             :variation-expression variation-expression
             :expression-type expression-type
             :value value)
  (redis/put-key redis-db
                 (expression-key-serializer variation-expression
                                            expression-type)
                 value))

(defn redis-expression-cache-get
  "Looks up expression with its type in the cache. Returns nil when not found."
  [variation-expression expression-type]
  (log/debug :fn :redis-expression-cache-get
             :variation-expression variation-expression
             :expression-type expression-type)
  (redis/get-key redis-db
                 (expression-key-serializer variation-expression
                                            expression-type)))

(defn redis-configured? []
  (System/getenv "CACHE_REDIS_URI"))

(defn with-retries
  "Tries to execute body-fn retry-count times."
  [retry-count retry-interval-ms body-fn]
  (try
    (body-fn)
    (catch Exception e
      (if (> retry-count 0)
        (do (log/info :fn :with-retries
                      :msg (format "body-fn failed, trying again in %s ms"
                                   retry-interval-ms))
            (Thread/sleep retry-interval-ms)
            (with-retries
              (dec retry-count)
              retry-interval-ms
              body-fn))
        (do (log/error :fn :with-retries :msg "Retry limit exceeded")
            (throw e))))))

(defn store-in-cache
  [variation-expression expression-type value]
  (cond
    (redis-configured?) (with-retries 12 5000 ; retry up to 1m, every 5s
                          #(redis-expression-cache-put
                            variation-expression
                            expression-type
                            value))
    :else (rocksdb/rocks-put! vicc-expr-db
                              (expression-key-serializer
                               variation-expression
                               expression-type)
                              value)))

(defn get-from-cache
  [variation-expression expression-type]
  (cond
    (redis-configured?) (with-retries 12 5000
                          #(redis-expression-cache-get
                            variation-expression
                            expression-type))
    :else (rocksdb/rocks-get vicc-expr-db
                             (expression-key-serializer
                              variation-expression
                              expression-type))))


(defn vrs-variation-for-expression
  "Accepts :hgvs, :spdi, or :cnv types of expressions.
   Example: HGVS or SPDI expressions. If type is :cnv, the expression should be a map.
   https://normalize.cancervariants.org/variation"
  ([variation-expression]
   (vrs-variation-for-expression variation-expression nil))
  ([variation-expression ^Keyword expression-type]
   (log/debug :fn :vrs-allele-for-variation :variation-expression variation-expression :expr-type expression-type)
   (let [cached-value (get-from-cache variation-expression expression-type)]
     (when ((comp not not) cached-value)
       (log/debug :fn :vrs-variation-for-expression
                  :cache-hit? true
                  :cached-value cached-value
                  :variation-expression variation-expression
                  :expr-type expression-type))
     ;; By default, tell the service to try hgvs. Will return as Text variation if unable to parse
     (if cached-value
       cached-value
       (doto (case expression-type
               :cnv (normalize-absolute-copy-number variation-expression)
               :spdi (normalize-canonical variation-expression :spdi)
               :hgvs (normalize-canonical variation-expression :hgvs)
               (normalize-canonical variation-expression :hgvs))
         ;; Error cases throw exception so are not persisted in cache
         (#(store-in-cache variation-expression expression-type %)))))))
