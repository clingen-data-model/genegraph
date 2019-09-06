(ns genegraph.source.graphql.gene-dosage
  (:require [genegraph.database.query :as q]))

(defn label [context args value]
  (q/ld-> value [:sepio/has-subject :sepio/has-subject :geno/is-feature-affected-by :skos/preferred-label]))

(defn wg-label [context args value]
  "Gene Dosage Working Group")

(defn classification-description [context args value]
  (str (q/ld1-> value [:sepio/has-object :rdfs/label]) " for dosage pathogenicity"))

(defn report-date [context args value]
  (q/ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

(defn evidence [context args value]
  (q/ld-> value [:sepio/has-evidence-line-with-item]))
