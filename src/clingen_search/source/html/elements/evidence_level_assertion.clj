(ns clingen-search.source.html.elements.evidence-level-assertion
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))

(defmethod e/paragraph :sepio/EvidenceLevelAssertion [assertion]
  (let []
    [:p ]))
