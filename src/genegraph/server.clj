(ns genegraph.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [genegraph.service :as service]
            [mount.core :as mount :refer [defstate]]
            [genegraph.sink.base :as base]
            [genegraph.sink.stream :as stream]
            [genegraph.migration :as migration]
            [genegraph.env :as env]
            [io.pedestal.log :as log])
  (:import com.google.firebase.FirebaseApp))

(def initialized? (atom false))

(def status-routes 
  {::server/routes
   [["/live"
     :get (fn [_] {:status 200 :body "server is live"})
     :route-name ::liveness]
    ["/ready"
     :get (fn [_] (if (and @initialized? (stream/consumers-up-to-date?))
                    {:status 200 :body "server is ready"}
                    {:status 503 :body "server is not ready"}))
     :route-name ::readiness]
    ["/env"
     :get (fn [_] {:status 200 :body (str env/environment)})
     :route-name ::env]]})

(defn start-server! []
  (let [service-map (case env/mode
                      "production" (service/prod-service)
                      "transformer" (service/transformer-service)
                      (service/dev-service))]
    (server/start 
     (server/create-server
      (merge-with into service-map status-routes)))))

(defstate server
  :start (start-server!)
  :stop (server/stop server))

(defstate firebase
  :start (FirebaseApp/initializeApp)
  :stop (.delete firebase))

(defn run-dev
  "Run a development-focused environment: skip connection to Kafka unless
  requested, watch for updates in base data."
  ;; Optional args so that it can be run from clj -X
  [& args]
  (env/log-environment)
  (mount.core/start-without #'genegraph.sink.event/stream-processing))

(defn run-server-genegraph
  [_]
  (log/info :fn :-main :message "Starting Genegraph")
  (mount.core/start #'server)
  (log/info :fn :-main :message "Pedestal initialized")
  (env/log-environment)
  (migration/populate-data-vol-if-needed)
  (log/info :fn :-main :message "Data volume exists")
  (mount.core/start)
  (log/info :fn :-main :message "All services started")
  (stream/wait-for-topics-up-to-date)
  (log/info :fn :-main :message "Topics up to date.")
  (migration/warm-resolver-cache)
  (reset! initialized? true)
  (log/info :fn :-main :message "Genegraph fully initialized, all systems go"))

(defn run-server-transformer
  [_]
  (log/info :fn :-main :message "Starting Genegraph Transformer")
  (mount.core/start #'server)
  (log/info :fn :-main :message "Pedestal initialized")
  (env/log-environment)
  (migration/populate-data-vol-if-needed)
  (log/info :fn :-main :message "Data volume exists")
  (mount.core/start-without #'genegraph.suggest.suggesters/suggestions
                            #'genegraph.source.graphql.common.cache/resolver-cache-db
                            #'genegraph.response-cache/cache-store)
  (log/info :fn :-main :message "All services started")
  (reset! initialized? true)
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
    (run-migration)))

