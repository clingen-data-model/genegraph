(ns genegraph.transform.clinvar.cancervariants
  (:require [cheshire.core :as json]
            [genegraph.database.names :as names :refer [prefix-ns-map]]
            [genegraph.rocksdb :as rocksdb]
            [genegraph.util.http-client :as ghttp :refer [http-get-with-cache]]
            [io.pedestal.log :as log]
            [mount.core :refer [defstate]])
  (:import (clojure.lang Keyword)
           (org.rocksdb RocksDB)))

(def cancer-variants-normalize-url
  "URL for cancervariants.org VRSATILE normalization.
  Returns a JSON document containing a variation_descriptor field along with other metadata."
  ;;"https://normalize.cancervariants.org/variation/normalize"
  "https://normalize.cancervariants.org/variation/to_vrs")

(def cancer-variants-url-to-canonical
  "URL for cancervariants.org VRS normalization.
  Returns a JSON document containing a CanonicalVariation under the canonical_variation field, along with other metadata."
  ;;"https://normalize.cancervariants.org/variation/normalize"
  "https://normalize.cancervariants.org/variation/to_canonical_variation")

(def canonical_spdi_to_categorical_variation
  "https://normalize.cancervariants.org/variation/canonical_spdi_to_categorical_variation")

(def absolute-copy-number-url
  "URL for cancervariants.org Absolute Copy Number normalization."
  "https://normalize.cancervariants.org/variation/parsed_to_abs_cnv")

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

(def vicc-db-name "cancervariants-cache.db")

(defstate ^RocksDB vicc-db
  :start (rocksdb/open vicc-db-name)
  :stop (rocksdb/close vicc-db))

(defn normalize-spdi [^String spdi-expression]
  (log/info :fn :normalize-spdi :variation-expression spdi-expression)
  (let [response (http-get-with-cache vicc-db
                                      canonical_spdi_to_categorical_variation
                                      {:query-params {"q" spdi-expression}})
        status (:status response)]
    (case status
      200 (let [body (-> response :body json/parse-string)]
            (log/debug :fn :vrs-allele-for-variation :body body)
            (-> body (get "categorical_variation") add-vicc-context))
      ;; Error case
      (log/error :fn :normalize-spdi :msg "Error in VRS normalization request" :status status :response response))))

(defn wrap-with-canonical-variation
  [variation-object]
  {"_id" (str (get variation-object "_id") "_canonicalwrap")
   "@type" "vrs:CanonicalVariation"
   "variation" variation-object})

(defn normalize-general
  [^String variation-expression]
  (log/info :fn :normalize-general :variation-expression variation-expression)
  (let [response (http-get-with-cache vicc-db
                                      cancer-variants-normalize-url
                                      {:query-params {"q" variation-expression}})
        status (:status response)]
    (case status
      200 (let [body (-> response :body json/parse-string)]
            (log/debug :fn :vrs-allele-for-variation :body body)
            (-> body (get "variations") first wrap-with-canonical-variation add-vicc-context))
      ;; Error case
      (log/error :fn :normalize-general :msg "Error in VRS normalization request" :status status :response response))))

(defn normalize-canonical
  [^String variation-expression ^Keyword expression-type]
  (log/info :fn :normalize-canonical :variation-expression variation-expression)
  (let [response (http-get-with-cache vicc-db
                                      cancer-variants-url-to-canonical
                                      {:query-params {"q" variation-expression
                                                      "fmt" (name expression-type)}})
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
                                      absolute-copy-number-url
                                      {:query-params
                                       (into {}
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
