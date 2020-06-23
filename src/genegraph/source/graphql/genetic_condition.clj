(ns genegraph.source.graphql.genetic-condition
  (:require [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(defresolver gene [args value]
  (:gene value))

(defresolver disease [args value]
  (:disease value))

(defresolver mode-of-inheritance [args value]
  (:mode-of-inheritance value))

(defresolver actionability-curations [args value]
  (curation/actionability-curations-for-genetic-condition value))

(defresolver gene-validity-curation [args value]
  (curation/gene-validity-curations value))

(defresolver gene-dosage-curation [args value]
  (curation/dosage-sensitivity-curations-for-genetic-condition value))
