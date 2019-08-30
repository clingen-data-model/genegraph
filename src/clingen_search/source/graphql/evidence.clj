(ns clingen-search.source.graphql.evidence
  (:require [clingen-search.database.query :as q]))

(defn source [context args value]
  (str (q/ld1-> value [:dcterms/source])))

(defn description [context args value]
  (q/ld1-> value [:dc/description]))
