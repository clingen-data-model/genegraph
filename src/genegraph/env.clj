(ns genegraph.env
  (:require [io.pedestal.log :as log]))
(def base-dir (or (System/getenv "GENEGRAPH_DATA_PATH")
                  (System/getenv "CG_SEARCH_DATA_VOL")))
(def data-version (System/getenv "GENEGRAPH_DATA_VERSION"))
;; May be rebound in the course of a migration
(def ^:dynamic data-vol (if data-version
                          (str base-dir "/" data-version)
                          base-dir))
(def dx-key-pass (System/getenv "SERVEUR_KEY_PASS"))
(def dx-topics (System/getenv "CG_SEARCH_TOPICS"))
(def dx-stage-jaas (System/getenv "DX_STAGE_JAAS"))
(def genegraph-bucket (System/getenv "GENEGRAPH_BUCKET"))
(def use-gql-cache (System/getenv "GENEGRAPH_GQL_CACHE"))

(defn log-environment []
  (log/info :fn :log-environment
            :data-vol data-vol
            :dx-key-pass (true? dx-key-pass)
            :dx-topics dx-topics))
