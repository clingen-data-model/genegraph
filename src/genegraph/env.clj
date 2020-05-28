(ns genegraph.env
  (:require [io.pedestal.log :as log]))
(def data-vol (System/getenv "CG_SEARCH_DATA_VOL"))
(def dx-key-pass (System/getenv "SERVEUR_KEY_PASS"))
(def dx-topics (System/getenv "CG_SEARCH_TOPICS"))
(def dx-stage-jaas (System/getenv "DX_STAGE_JAAS"))

(defn log-environment []
  (log/info :fn :log-environment
            :data-vol data-vol
            :dx-key-pass (true? dx-key-pass)
            :dx-topics dx-topics))
