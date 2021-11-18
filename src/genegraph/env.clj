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
(def dx-jaas-config (System/getenv "DX_JAAS_CONFIG"))
(def genegraph-bucket (System/getenv "GENEGRAPH_BUCKET"))
(def use-gql-cache (System/getenv "GENEGRAPH_GQL_CACHE"))
(def mode (System/getenv "GENEGRAPH_MODE"))
(def validate-events (Boolean/valueOf (System/getenv "GENEGRAPH_VALIDATE_EVENTS")))
(def use-response-cache (System/getenv "GENEGRAPH_RESPONSE_CACHE"))
(def genegraph-version (System/getenv "GENEGRAPH_IMAGE_VERSION"))
(def database-build-mode (System/getenv "GENEGRAPH_DATABASE_BUILD_MODE"))
(def graphql-logging-topic (System/getenv "GENEGRAPH_GQL_LOGGING_TOPIC"))
(def use-experimental-schema (System/getenv "GENEGRAPH_EXPERIMENTAL_SCHEMA"))
(def batch-event-sources (System/getenv "GENEGRAPH_BATCH_EVENT_SOURCES"))
(def transformer-mode (Boolean/valueOf (System/getenv "GENEGRAPH_TRANSFORMER_MODE")))

(def dx-key-pass (System/getenv "SERVEUR_KEY_PASS"))

(def environment {:data-vol data-vol
                  :dx-topics dx-topics
                  :genegraph-data-version data-version
                  :genegraph-bucket genegraph-bucket
                  :genegraph-gql-cache use-gql-cache
                  :genegraph-response-cache use-response-cache
                  :genegraph-mode mode
                  :genegraph-validate-events validate-events
                  :genegraph-version genegraph-version
                  :graphql-logging-topic graphql-logging-topic
                  :genegraph-transformer-mode transformer-mode
                  :batch-event-sources batch-event-sources})

(defn log-environment []
  (log/info :fn :log-environment
            :env environment))
