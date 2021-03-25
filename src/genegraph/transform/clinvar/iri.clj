; Defines clinvar iri namespaces and iri builder functions
(ns genegraph.transform.clinvar.iri
  (:require [genegraph.database.names :refer [prefix-ns-map]]
            [genegraph.transform.clinvar.util :refer :all]))

(def clinvar-variation "https://identifiers.org/clinvar:")
(def clinvar-vcv "https://www.ncbi.nlm.nih.gov/clinvar/variation/")
(def clinvar-assertion "https://identifiers.org/clinvar.submission:")

; clingen terms
(def cgterms (prefix-ns-map "cgterms"))
;(def prefix-cv "https://www.ncbi.nlm.nih.gov/clinvar/")

(def submitter (path-join cgterms "clinvar.submitter/"))
(def submission (path-join cgterms "assertion_set/"))
(def trait-set (path-join cgterms "clinvar.trait_set/"))
(def trait (path-join cgterms "clinvar.trait/"))
(def rcv (path-join cgterms "clinvar.rcv_accession/"))
(def variation-archive (path-join cgterms "clinvar.variation_archive/"))
(def release-sentinel (path-join cgterms "clinvar_release/"))

; Submitted assertion sub-nodes
(def clinical-assertion-observation (path-join cgterms "clinvar.clinical_assertion_observation/"))
(def clinical-assertion-trait-set (path-join cgterms "clinvar.clinical_assertion_trait_set/"))
(def clinical-assertion-trait (path-join cgterms "clinvar.clinical_assertion_trait/"))
(def clinical-assertion-variation (path-join cgterms "clinvar.clinical_assertion_variation/"))

; Normalized assertion nodes
(def trait-set (path-join cgterms "clinvar.trait_set/"))
(def trait (path-join cgterms "clinvar.trait/"))
;(def variation (path-join cgterms "clinvar.variation/"))
