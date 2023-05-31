(ns genegraph.main
  (:require [genegraph.env :as env]
            [genegraph.migration :as migration]
            [genegraph.server :as server]
            [genegraph.sink.stream :as stream]
            [genegraph.source.registry.vrs-registry :as vrs-registry]
            [genegraph.source.snapshot.core :as snapshot]
            [io.pedestal.log :as log]
            [mount.core :refer [defstate]])
  (:gen-class))

(defonce watch-streams (atom false))

(defn monitor-stream-loop []
  (while @watch-streams
    (if-not (stream/consumers-are-polling?)
      (log/error :message "consumer poll interval exceeds maximum drift" :consumers @stream/consumers))
    (Thread/sleep (* 1000 10))))

(defstate stream-watcher
  :start (do
           (reset! watch-streams true)
           (.start (Thread. monitor-stream-loop)))
  :stop (reset! watch-streams false))

(defn run-dev
  "Run a development-focused environment: skip connection to Kafka unless
  requested, watch for updates in base data."
  ;; Optional args so that it can be run from clj -X
  [& args]
  (env/log-environment)
  (mount.core/start-without #'genegraph.sink.event/stream-processing
                            #'stream-watcher))

(defn run-server-genegraph
  [_]
  (log/info :fn :-main :message "Starting Genegraph")
  (mount.core/start #'genegraph.server/server)
  (log/info :fn :-main :message "Pedestal initialized")
  (env/log-environment)
  (migration/populate-data-vol-if-needed)
  (log/info :fn :-main :message "Data volume exists")
  (mount.core/start)
  (log/info :fn :-main :message "All services started")
  (stream/wait-for-topics-up-to-date)
  (log/info :fn :-main :message "Topics up to date.")
  (migration/warm-resolver-cache)
  (reset! server/initialized? true)
  (log/info :fn :-main :message "Genegraph fully initialized, all systems go"))

(defn run-server-transformer
  [_]
  (log/info :fn :-main :message "Starting Genegraph Transformer")
  (mount.core/start #'genegraph.server/server)
  (log/info :fn :-main :message "Pedestal initialized")
  (env/log-environment)
  (migration/populate-data-vol-if-needed)
  (log/info :fn :-main :message "Data volume exists")
  (mount.core/start-without #'genegraph.suggest.suggesters/suggestions
                            #'genegraph.source.graphql.common.cache/resolver-cache-db
                            #'genegraph.response-cache/cache-store)
  (log/info :fn :-main :message "All services started")
  (reset! server/initialized? true)
  (log/info :fn :-main :message "Genegraph Transformer fully initialized, all systems go"))


(defn run-migration
  []
  (log/info :fn :-main :message "Creating migration")
  (env/log-environment)
  (when env/migration-data-version
    (with-redefs [env/data-vol env/migration-data-vol
                  env/data-version env/migration-data-version]
      (migration/populate-data-vol-if-needed)))
  (migration/create-migration))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (if (= 0 (count args))
    (if (env/transformer-mode?)
      (run-server-transformer nil)
      (run-server-genegraph nil))
    (cond
      (= "snapshot" (first args)) (apply snapshot/-main (rest args))
      (= "vrs-cache" (first args)) (apply vrs-registry/-main (rest args))
      :else (run-migration))))
