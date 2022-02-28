(ns genegraph.transform.clinvar.cancervariants
  (:require [genegraph.database.names :as names :refer [prefix-ns-map]]
            [clj-http.client :as http]
            [io.pedestal.log :as log]
            [cheshire.core :as json])
  (:import (clojure.lang Keyword)))

(def cancer-variants-normalize-url
  "URL for cancervariants.org VRSATILE normalization.
  Returns a JSON document containing a variation_descriptor field along with other metadata."
  ;"https://normalize.cancervariants.org/variation/normalize"
  "https://normalize.cancervariants.org/variation/toVRS"
  )

(def canonical_spdi_to_categorical_variation
  "https://normalize.cancervariants.org/variation/canonical_spdi_to_categorical_variation")

(defn add-vicc-context [val]
  (assoc val "@context" {"id" {"@id" "@id"},
                         "_id" {"@id" "@id"},
                         "@vocab" (get prefix-ns-map "vrs") ;"https://vrs.ga4gh.org/"
                         "normalize.variation" {"@id" "https://github.com/cancervariants/variation-normalization/"
                                                "@prefix" true}}))

(defn normalize-spdi [^String spdi-expression]
  (log/info :fn ::normalize-spdi :variation-expression spdi-expression)
  (let [response (http/get canonical_spdi_to_categorical_variation {:query-params {"q" spdi-expression}})
        status (:status response)]
    (case status
      200 (let [body (-> response :body json/parse-string)]
            (log/info :fn ::vrs-allele-for-variation :body body)
            (-> body (get "categorical_variation") add-vicc-context))
      ; Error case
      (log/error :fn ::vrs-allele-for-variation :msg "Error in VRS normalization request" :status status :response response))))

(defn normalize-general
  [^String variation-expression]
  (log/info :fn ::normalize-general :variation-expression variation-expression)
  (let [response (http/get cancer-variants-normalize-url {:query-params {"q" variation-expression}})
        status (:status response)]
    (case status
      200 (let [body (-> response :body json/parse-string)]
            (log/info :fn ::vrs-allele-for-variation :body body)
            (-> body (get "variations") first add-vicc-context))
      ; Error case
      (log/error :fn ::vrs-allele-for-variation :msg "Error in VRS normalization request" :status status :response response))))

(defn vrs-variation-for-expression
  "`variation` should be a string understood by the VICC variant normalization service.
  Example: HGVS or SPDI expressions.
  https://normalize.cancervariants.org/variation"
  ([^String variation-expression]
   (vrs-variation-for-expression variation-expression nil))
  ([^String variation-expression ^Keyword expression-type]
   (log/info :fn ::vrs-allele-for-variation :variation-expression variation-expression :expr-type expression-type)
   (case expression-type
     :spdi (normalize-spdi variation-expression)
     :hgvs (normalize-general variation-expression)
     (normalize-general variation-expression))))

