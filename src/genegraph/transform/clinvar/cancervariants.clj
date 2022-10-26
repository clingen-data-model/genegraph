(ns genegraph.transform.clinvar.cancervariants
  (:require [cheshire.core :as json]
            [genegraph.database.names :as names :refer [prefix-ns-map]]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.util.http-client :as ghttp :refer [http-get-with-cache]]
            [io.pedestal.log :as log]
            [mount.core :refer [defstate]])
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

(defstate ^RocksDB vicc-db
  :start (rocksdb/open vicc-db-name)
  :stop (rocksdb/close vicc-db))

(defstate ^RocksDB vicc-expr-db
  :start (rocksdb/open vicc-expr-db-name)
  :stop (rocksdb/close vicc-expr-db))

(def normalize-canonical-cache
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
  [^String variation-expression ^Keyword expression-type]
  (log/debug :fn :normalize-canonical :variation-expression variation-expression)
  (let [response (http-get-with-cache vicc-db
                                      url-to-canonical
                                      {:query-params {"q" variation-expression
                                                      "fmt" (name expression-type)
                                                      "untranslatable_returns_text" true}})
        status (:status response)]
    (case status
      200 (let [body (-> response :body json/parse-string)]
            (log/debug :fn :normalize-canonical :body body)
            (-> body (get "canonical_variation") add-vicc-context))
      ;; Error case
      (log/error :fn :normalize-canonical :msg "Error in VRS normalization request" :status status :response response))))

(defn normalize-absolute-copy-number
  [input-map]
  (log/info :fn :normalize-absolute-copy-number :input-map input-map)
  (let [response (http-get-with-cache vicc-db
                                      url-absolute-cnv
                                      {:query-params
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
            (log/info :fn :normalize-absolute-copy-number :body body)
            (assert not-empty (-> body (get "absolute_copy_number")))
            (-> body (get "absolute_copy_number") add-vicc-context))
      ;; Error case
      (log/error :fn :normalize-absolute-copy-number
                 :msg "Error in VRS normalization request"
                 :status status
                 :response response))))

(defn vrs-variation-for-expression
  "`variation` should be a string understood by the VICC variant normalization service.
  Example: HGVS or SPDI expressions. If type is :cnv, the expression should be a map.
  https://normalize.cancervariants.org/variation"
  ([^String variation-expression]
   (vrs-variation-for-expression variation-expression nil))
  ([^String variation-expression ^Keyword expression-type]
   (log/debug :fn :vrs-allele-for-variation :variation-expression variation-expression :expr-type expression-type)
   (case expression-type
     :cnv (normalize-absolute-copy-number variation-expression)
     :spdi (normalize-canonical variation-expression :spdi)
     :hgvs (normalize-canonical variation-expression :hgvs)
     ;; By default, tell the service to try hgvs. Will return as Text variation if unable to parse
     (normalize-canonical variation-expression :hgvs))))
