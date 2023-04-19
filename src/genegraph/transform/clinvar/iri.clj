; Defines clinvar iri namespaces and iri builder functions
(ns genegraph.transform.clinvar.iri
  (:require [genegraph.database.names :refer [prefix-ns-map]]
            [genegraph.transform.clinvar.util :refer :all]))

(def clinvar-variation "https://identifiers.org/clinvar:")
(def clinvar-vcv "https://www.ncbi.nlm.nih.gov/clinvar/variation/")
(def clinvar-assertion "https://identifiers.org/clinvar.submission:")

; clingen terms
(def cgterms (prefix-ns-map "cgterms"))
(defn ns-cg [term] (str cgterms term))
;(def prefix-cv "https://www.ncbi.nlm.nih.gov/clinvar/")

(def submitter (path-join cgterms "clinvar.submitter/"))
(def submission (path-join cgterms "assertion_set/"))
(def rcv (path-join cgterms "clinvar.rcv_accession/"))
(def variation-archive (path-join cgterms "clinvar.variation_archive/"))
(def release-sentinel (path-join cgterms "clinvar_release/"))
(def clinvar-gene (path-join cgterms "clinvar.gene/"))

; Submitted assertion sub-nodes
(def clinical-assertion-observation (path-join cgterms "clinvar.clinical_assertion_observation/"))
(def clinical-assertion-trait-set (path-join cgterms "clinvar.clinical_assertion_trait_set/"))
(def clinical-assertion-trait (path-join cgterms "clinvar.clinical_assertion_trait/"))
(def clinical-assertion-variation (path-join cgterms "clinvar.clinical_assertion_variation/"))

; Normalized assertion nodes
(def trait-set (path-join cgterms "clinvar.trait_set/"))
(def trait (path-join cgterms "clinvar.trait/"))
;(def variation (path-join cgterms "clinvar.variation/"))

(defn parse-vd-iri
  "http://dataexchange.clinicalgenome.org/terms/VariationDescriptor_436617.2019-07-01
   -> {:id 436617 :version 2019-07-01}"
  [iri]
  (let [iri (str iri)
        type-prefix "http://dataexchange.clinicalgenome.org/terms/VariationDescriptor_"]
    (assert (.startsWith iri type-prefix)
            {:msg "Failed assertion"
             :iri iri})
    (let [id-plus-version (subs iri (count type-prefix))]
      (if (.contains id-plus-version ".")
        (let [id (subs id-plus-version 0 (.indexOf id-plus-version "."))
              version (subs id-plus-version (+ 1 (.indexOf id-plus-version ".")))]
          {:id id
           :version version})
        {:id id-plus-version}))))

(defn parse-clinvar-resource-iri
  "http://dataexchange.clinicalgenome.org/terms/VariationDescriptor_436617.2019-07-01
   -> {:id 436617 :version 2019-07-01}"
  [iri]
  (let [iri (str iri)
        ns-prefix "http://dataexchange.clinicalgenome.org/terms/"
        [matched & groups] (re-find (re-pattern (str "^" ns-prefix "(.+_)(\\w+)\\.([\\w-]+)$")) iri)
        [type-prefix id version] groups]
    (merge {:id id
            :ns-prefix ns-prefix
            :type-prefix type-prefix}
           (when version
             {:version version}))))
