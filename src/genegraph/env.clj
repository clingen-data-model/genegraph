(ns genegraph.env
  "Read configuration from the process environment."
  (:require [clojure.string  :as str]
            [io.pedestal.log :as log]))

(def base-dir (let [base-dir-env (or (System/getenv "GENEGRAPH_DATA_PATH")
                                     (System/getenv "CG_SEARCH_DATA_VOL"))]
                (if (empty? base-dir-env)
                  (do (log/warn :msg "'genegraph.env/base-dir is defaulting to user.dir/data")
                      (str (System/getProperty "user.dir") "/data"))
                  base-dir-env)))
(def data-version (System/getenv "GENEGRAPH_DATA_VERSION"))
;; May be rebound in the course of a migration
(def ^:dynamic data-vol (if data-version
                          (str base-dir "/" data-version)
                          base-dir))
(def ^:private dx-topics (System/getenv "CG_SEARCH_TOPICS"))
(def dx-jaas-config (System/getenv "DX_JAAS_CONFIG"))
(def genegraph-bucket (System/getenv "GENEGRAPH_BUCKET"))
(def use-gql-cache (Boolean/valueOf (System/getenv "GENEGRAPH_GQL_CACHE")))
(def mode (System/getenv "GENEGRAPH_MODE"))
(def validate-events (Boolean/valueOf (System/getenv "GENEGRAPH_VALIDATE_EVENTS")))
(def use-response-cache (Boolean/valueOf (System/getenv "GENEGRAPH_RESPONSE_CACHE")))
(def genegraph-image-version (System/getenv "GENEGRAPH_IMAGE_VERSION"))
(def database-build-mode (System/getenv "GENEGRAPH_DATABASE_BUILD_MODE"))
(def graphql-logging-topic (System/getenv "GENEGRAPH_GQL_LOGGING_TOPIC"))
(def use-experimental-schema (Boolean/valueOf (System/getenv "GENEGRAPH_EXPERIMENTAL_SCHEMA")))
(def batch-event-sources (System/getenv "GENEGRAPH_BATCH_EVENT_SOURCES"))

;; When defined, this is a previous migration archive name (without the '.tar.gz')
;; from where the base data for a new migration will originate.
(def migration-data-version (System/getenv "GENEGRAPH_MIGRATION_VERSION"))
(def migration-data-vol (if migration-data-version (str base-dir "/" migration-data-version) nil))

(def dx-key-pass (System/getenv "SERVEUR_KEY_PASS"))

(def environment {:data-vol data-vol
                  :dx-topics (set (map keyword (str/split dx-topics #";")))
                  :genegraph-data-version data-version
                  :genegraph-bucket genegraph-bucket
                  :genegraph-gql-cache use-gql-cache
                  :genegraph-response-cache use-response-cache
                  :genegraph-mode mode
                  :genegraph-validate-events validate-events
                  :genegraph-image-version genegraph-image-version
                  :graphql-logging-topic graphql-logging-topic
                  :batch-event-sources batch-event-sources
                  :use-experimental-schema use-experimental-schema
                  :migration-data-version migration-data-version
                  :migration-data-volume migration-data-vol})

(defn log-environment []
  (log/info :fn :log-environment
            :env environment))

(defn transformer-mode? []
  (= "transformer" mode))
