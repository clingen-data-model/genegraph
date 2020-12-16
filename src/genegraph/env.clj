(ns genegraph.env
  (:require [io.pedestal.log :as log]))
(def base-dir (or (System/getenv "GENEGRAPH_DATA_PATH")
                  (System/getenv "CG_SEARCH_DATA_VOL")))
(def data-version (System/getenv "GENEGRAPH_DATA_VERSION"))
;; May be rebound in the course of a migration
(def ^:dynamic data-vol (if data-version
                          (str base-dir "/" data-version)
                          base-dir))
(def dx-topics (System/getenv "CG_SEARCH_TOPICS"))
(def dx-stage-jaas (System/getenv "DX_STAGE_JAAS"))
(def genegraph-bucket (System/getenv "GENEGRAPH_BUCKET"))
(def use-gql-cache (System/getenv "GENEGRAPH_GQL_CACHE"))
(def mode (System/getenv "GENEGRAPH_MODE"))
(def validate-events (System/getenv "GENEGRAPH_VALIDATE_EVENTS"))
(def use-response-cache (System/getenv "GENEGRAPH_RESPONSE_CACHE"))
(def genegraph-version (System/getenv "GENEGRAPH_IMAGE_VERSION"))

(def environment {:data-vol data-vol
                  :dx-topics dx-topics
                  :genegraph-data-version data-version
                  :genegraph-bucket genegraph-bucket
                  :genegraph-gql-cache use-gql-cache
                  :genegraph-response-cache use-response-cache
                  :genegraph-mode mode
                  :genegraph-validate-events validate-events
                  :genegraph-version genegraph-version})
  

(defn log-environment []
  (log/info :fn :log-environment
            :env environment))
