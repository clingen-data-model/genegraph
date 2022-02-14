(ns genegraph.transform.clinvar.cancervariants
  (:require [clj-http.client :as http]
            [io.pedestal.log :as log]
            [cheshire.core :as json]))

(def cancer-variants-normalize-url
  "URL for cancervariants.org VRSATILE normalization.
  Returns a JSON document containing a variation_descriptor field along with other metadata."
  "https://normalize.cancervariants.org/variation/normalize")

(defn jsonld-ify [val]
  (assoc val "@context" {"id" {"@id" "@id"},
                         "_id" {"@id" "@id"},
                         "@vocab" "https://vrs.ga4gh.org/"
                         "normalize.variation" {"@id" "https://github.com/cancervariants/variation-normalization/"
                                                "@prefix" true}}))

(defn vrs-allele-for-variation
  "`variation` should be a string understood by the VICC variant normalization service.
  Example: HGVS or SPDI expressions.
  https://normalize.cancervariants.org/variation"
  [variation-expression]
  (log/info :fn ::vrs-allele-for-variation :variation-expression variation-expression)
  (let [response (http/get cancer-variants-normalize-url {:query-params {"q" variation-expression}})
        status (:status response)]
    (case status
      200 (let [body (-> response :body json/parse-string)]
            (log/debug :fn ::vrs-allele-for-variation :body body)
            (jsonld-ify (get body "variation_descriptor")))
      ; Error case
      (log/error :fn ::vrs-allele-for-variation :msg "Error in VRS normalization request" :status status :response response))))
