(ns genegraph.transform.clinvar.common
  (:require [clojure.tools.logging :as log]
            [genegraph.database.names :refer [local-property-names local-class-names prefix-ns-map]]))

(defmulti transform-clinvar :genegraph.transform.clinvar/format)

(defmulti clinvar-to-jsonld :genegraph.transform.clinvar/format)

(defn variation-geno-type
  [variation-type]
  (cond (= "SimpleAllele" variation-type)
        :geno/Allele
        (= "Haplotype" variation-type)
        :geno/Haplotype
        (= "Genotype" variation-type)
        :geno/Genotype
        :default (do (log/error "Unknown variation type")
                     :geno/Allele)))

(defn contribution-role
  "Define contribution type for different entities"
  []
  (throw (UnsupportedOperationException. "Not yet implemented")))

(def vcv-review-status-to-evidence-strength-map
  "Converts clinvar textual VCV review status to a numerical evidence strength
  https://www.ncbi.nlm.nih.gov/clinvar/docs/review_status/"
  {"practice guideline"                                   4
   "reviewed by expert panel"                             3
   "criteria provided, multiple submitters, no conflicts" 2
   "criteria provided, single submitter"                  1
   "criteria provided, conflicting interpretations"       1
   "no assertion criteria provided"                       0
   "no assertion for the individual variant"              0
   "no assertion provided"                                0})
(def scv-review-status-to-evidence-strength-map
  "Maps clinvar textual SCV review status to a numerical evidence strength.
  https://www.ncbi.nlm.nih.gov/clinvar/docs/review_status/"
  {"practice guideline"                      4
   "reviewed by expert panel"                3
   "criteria provided, single submitter"     1
   "no assertion criteria provided"          0
   "no assertion for the individual variant" 0
   "no assertion provided"                   0})

(defn genegraph-kw-to-iri
  "Uses property-names and class-names to try to resolve keywords in `m`.
  Map keys are resolved against property names. Map values are resolved against class names.

  If a map value is a map, recurses on it.

  If a keyword cannot be resolved, converts it to a string. This may be undesirable for namespaced keywords."
  [m]
  (letfn [(resolve-key [k]
            (if (keyword? k)
              (if (some #(= k %) (keys local-property-names))
                (let [mapped-k (local-property-names k)]
                  (assert (not (nil? mapped-k)) (format "%s mapped from %s was nil" k mapped-k))
                  (str mapped-k))
                (name k))
              k))
          (resolve-value [v]
            (cond (map? v) (genegraph-kw-to-iri v)
                  (vector? v) (map #(resolve-value %) v)
                  (keyword? v) (if (some #(= v %) (keys local-class-names))
                                 (let [mapped-v (local-class-names v)]
                                   (assert (not (nil? mapped-v))) (format "%s mapped from %s was nil" v mapped-v)
                                   (str mapped-v))
                                 (name v))
                  :default v))]
    (into {} (map (fn [[k v]]
                    ; Take each k and v that are keywords and try to resolve k against local-property-names, and
                    ; v against local-class-names. If keyword not in those maps, convert keyword to string (name)
                    (let [k2 (resolve-key k)
                          v2 (resolve-value v)]
                      (log/debugf "%s -> %s, %s -> %s" k k2 v v2)
                      [k2 v2]))
                  m)))
  )
