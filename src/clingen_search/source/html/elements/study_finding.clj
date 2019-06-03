(ns clingen-search.source.html.elements.study-finding
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))


(defmethod e/row :sepio/StudyFinding [finding]
  [:div.columns
   [:div.column [:a {:href (-> finding :dcterms/source first str)} (-> finding :dc/description first)]]])
