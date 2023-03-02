(ns genegraph.transform.clinvar.cancervariants
  "Things for interacting with the cancervariants.org normalization service."
  (:require [cheshire.core :as json]
            [clj-http.client :as http-client]
            [genegraph.database.names :as names :refer [prefix-ns-map]]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.source.registry.redis :as redis]
            [genegraph.source.registry.rocks-registry :as rocks-registry]
            [genegraph.transform.clinvar.common :refer [with-retries]]
            [io.pedestal.log :as log]
            [mount.core :as mount]
            [genegraph.transform.clinvar.hgvs :as hgvs])
  (:import (clojure.lang Keyword)))

(def variation-normalizer-base-url
  (let [url (System/getenv "VARIATION_NORM_URL")]
    (when (empty? url)
      (log/error :msg "VARIATION_NORM_URL not defined"))
    url))

(def url-to-canonical
  "URL for cancervariants.org VRS normalization.
  Returns a JSON document containing a CanonicalVariation under the canonical_variation field, along with other metadata."
  (str variation-normalizer-base-url "/to_canonical_variation"))

(def url-absolute-cnv
  "URL for cancervariants.org Absolute Copy Number normalization."
  (str variation-normalizer-base-url "/parsed_to_abs_cnv"))

(def url-relative-cnv
  "URL for cancervariants.org Relative Copy Number normalization."
  (str variation-normalizer-base-url "/hgvs_to_relative_copy_number"))

(def vicc-context
  {"id" {"@id" "@id"},
   "_id" {"@id" "@id"},
   "type" {"@id" "@type"
           "@type" "@id"}
   "@vocab" (get prefix-ns-map "vrs")
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
(def vicc-expr-db-name "cancervariants-expr-cache.db")

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

(defn clinvar-copy-class-to-EFO
  "Returns an EFO CURIE for a copy class str"
  [copy-class-str]
  ({"Deletion" "EFO:0030067"
    "Duplication" "EFO:0030070"}
   copy-class-str))

(defn normalize-relative-copy-number
  [input-map]
  (log/info :fn :normalize-relative-copy-number :input-map input-map)
  (let [{hgvs :hgvs} input-map
        {expr :expr
         type :type
         label :label
         location :location
         copy-class :copy-class} hgvs
        efo-copy-class (clinvar-copy-class-to-EFO copy-class)
        {start :start
         stop :stop
         variant-length :variant-length} location]
    (log/info :fn :normalize-relative-copy-number :expr expr :copy-class copy-class)
    (let [response (http-client/get url-relative-cnv
                                    {:throw-exceptions false
                                     :query-params
                                     {"hgvs_expr" expr
                                      "relative_copy_class" efo-copy-class
                                      "untranslatable_returns_text" true}})
          status (:status response)]
      (case status
        200 (let [body (-> response :body json/parse-string)]
              (log/debug :fn :normalize-relative-copy-number :body body)
              (assert not-empty (-> body (get "relative_copy_number")))
              (-> body (get "relative_copy_number") add-vicc-context))
        (throw (ex-info "Error in VRS normalization request"
                        {:fn :normalize-relative-copy-number
                         :status status
                         :response response
                         :input-map input-map}))))))

(def redis-opts
  "Pool opts:
   https://github.com/ptaoussanis/carmine/blob/e4835506829ef7fe0af68af39caef637e2008806/src/taoensso/carmine/connections.clj#L146
   Can pass a predefined pool and shut this down when finished with it
   https://github.com/ptaoussanis/carmine/commit/a1d0c4ec1dd4848a9323eaa149ab284509664515
   Note: nil .spec values defaults to 127.0.0.1:6379"
  {:pool {#_(comment Default pool options)}
   :spec {:uri (System/getenv "CACHE_REDIS_URI")}})

(defn redis-configured? []
  (System/getenv "CACHE_REDIS_URI"))



(mount/defstate cache-db
  "Has a :db and :type. :db is either a redis conn spec, or an open RocksDB handle object"
  :start (letfn [(use-redis [] {:type :redis
                                :db redis-opts})
                 (use-rocks [] {:type :rocksdb
                                :db (rocksdb/open vicc-expr-db-name)})
                 (use-rocks-http [] {:type :rocksdb-http
                                     :db {:uri rocks-registry/rocksdb-http-uri}})]
           (or (when (rocks-registry/rocks-http-configured?)
                 (loop [left (* 5 60)]
                   (if (rocks-registry/rocks-http-connectable?)
                     (use-rocks-http)
                     (do (log/error :msg (str "RocksDB HTTP is configured but not connectable")
                                    :uri rocks-registry/rocksdb-http-uri)
                         (when (< 0 left)
                           (log/info :msg "Waiting for RocksDB HTTP to become available" :retries-left left)
                           (Thread/sleep 1000)
                           (recur (dec left)))))))
               (when (redis-configured?)
                 (loop [left (* 5 60)]
                   (if (redis/connectable? redis-opts)
                     (use-redis)
                     (do (log/error :msg "Redis is configured but not connectable."
                                    :redis-opts redis-opts)
                         (when (< 0 left)
                           (log/info :msg "Waiting for Redis to become available" :retries-left left)
                           (Thread/sleep 1000)
                           (recur (dec left)))))))
               (do (log/info :msg "Using RocksDB" :rocks-path vicc-expr-db-name)
                   (use-rocks))))
  :stop (case (:type cache-db)
          :redis (log/info :msg "No close implemented for redis client")
          :rocksdb (rocksdb/close (:db cache-db))
          :rocksdb-http (log/info :msg "No close implemented for rocksdb http")
          (throw (ex-info (str "Unknown cache-db type: " (:type cache-db))
                          {:cache-db cache-db}))))

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
  (assert (= :redis (:type cache-db)))
  (redis/put-key (:db cache-db)
                 (expression-key-serializer variation-expression
                                            expression-type)
                 value))

(defn redis-expression-cache-get
  "Looks up expression with its type in the cache. Returns nil when not found."
  [variation-expression expression-type]
  (log/debug :fn :redis-expression-cache-get
             :variation-expression variation-expression
             :expression-type expression-type)
  (assert (= :redis (:type cache-db)))
  (redis/get-key (:db cache-db)
                 (expression-key-serializer variation-expression
                                            expression-type)))

(defn store-in-cache
  "Puts the value in the cache, keyed by the first two arguments.
   Overwrites any existing value."
  [variation-expression expression-type value]
  (case (:type cache-db)
    :redis (with-retries 12 5000 ; retry up to 1m, every 5s
             #(redis-expression-cache-put
               variation-expression
               expression-type
               value))
    :rocksdb (rocksdb/rocks-put! (:db cache-db)
                                 (expression-key-serializer
                                  variation-expression
                                  expression-type)
                                 value)
    :rocksdb-http (with-retries 12 5000
                    #(rocks-registry/set-key-remote (-> cache-db :db :uri)
                                                    [variation-expression
                                                     expression-type]
                                                    value))))

(defn get-from-cache
  "Returns nil if no cache entry matches the arguments"
  [variation-expression expression-type]
  (case (:type cache-db)
    :redis (with-retries 12 5000
             #(redis-expression-cache-get
               variation-expression
               expression-type))
    :rocksdb (-> (rocksdb/rocks-get (:db cache-db)
                                    (expression-key-serializer
                                     variation-expression
                                     expression-type))
                 (#(when (not= :genegraph.rocksdb/miss %) %)))
    :rocksdb-http (with-retries 12 5000
                    #(rocks-registry/get-key-remote (-> cache-db :db :uri)
                                                    [variation-expression
                                                     expression-type]))
    (throw (ex-info (str "Unknown cache-db type: " (:type cache-db))
                    {:cache-db cache-db}))))

(defn vrs-variation-for-expression
  "Accepts :hgvs, :spdi, or :cnv types of expressions.
   Example: HGVS or SPDI expressions. If type is :cnv, the expression should be a map.
   https://normalize.cancervariants.org/variation"
  ([variation-expression]
   (vrs-variation-for-expression variation-expression nil))
  ([variation-expression ^Keyword expression-type]
   (log/info :fn :vrs-allele-for-variation :variation-expression variation-expression :expr-type expression-type)
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
               :cnv (case (:copy-number-type variation-expression)
                      :absolute (normalize-absolute-copy-number variation-expression)
                      :relative (normalize-relative-copy-number variation-expression)
                      (throw (ex-info (format "Invalid :copy-number-type %s"
                                              (:copy-number-type variation-expression))
                                      {:variation-expression variation-expression})))
               :spdi (normalize-canonical variation-expression :spdi)
               :hgvs (normalize-canonical variation-expression :hgvs)
               (normalize-canonical variation-expression :hgvs))
         ;; Error cases throw exception so are not persisted in cache
         (#(store-in-cache variation-expression expression-type %)))))))
