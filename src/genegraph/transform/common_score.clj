(ns genegraph.transform.common-score
  (:require [genegraph.database.load :as l])
  (:import java.time.Instant))

(def symbol-query "select ?s where { { ?s a :so/Gene . ?s :skos/preferred-label ?gene } union { ?s a :so/Gene . ?s :skos/hidden-label ?gene } }")

(defn date-time-now []
  (str (Instant/now)))

(defn common-row-to-triples [gene-uri rdf-class score date-imported org-url]
  (let [blank-is-about (l/blank-node)
        blank-contribution (l/blank-node)
        blank-agent (l/blank-node)]
    (concat [[blank-is-about :iao/is-about gene-uri]
             [blank-is-about :rdf/type rdf-class]
             [blank-is-about :sepio/confidence-score score]
             [blank-is-about :sepio/qualified-contribution blank-contribution]
             [blank-is-about :sepio/has-contributor blank-agent]
             [blank-contribution :rdf/type :sepio/Contribution]
             [blank-contribution :sepio/date-updated date-imported]
             [blank-contribution :sepio/has-agent blank-agent]
             [blank-agent :rdf/type :foaf/Organization]
             [blank-agent :skos/preferred-label org-url]])))

