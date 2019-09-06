(ns genegraph.source.html.elements.study-finding
  (:require [genegraph.source.html.elements :as e]
            [genegraph.database.query :as q]))


(defmethod e/row :sepio/StudyFinding [finding]
  [:div.columns
   [:div.column [:a {:href (-> finding :dcterms/source first str)} (-> finding :dc/description first)]]])
