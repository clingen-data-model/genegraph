(ns genegraph.source.graphql.genetic-condition
  (:require [genegraph.source.graphql.common.curation :as curation]))

(defn gene [context args value]
  (:gene value))

(defn disease [context args value]
  (:disease value))

(defn mode-of-inheritance [context args value]
  (:mode-of-inheritance value))

(defn actionability-curations [context args value]
  (curation/actionability-curations-for-genetic-condition value))

(defn gene-validity-curation [context args value]
  (curation/gene-validity-curations value))

(defn gene-dosage-curation [context args value]
  (curation/dosage-sensitivity-curations-for-genetic-condition value))
