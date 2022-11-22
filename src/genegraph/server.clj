(ns genegraph.server
  (:gen-class) ; for -main method in uberjar
  (:require [genegraph.env :as env]
            [genegraph.service :as service]
            [genegraph.sink.stream :as stream]
            [io.pedestal.http :as server]
            [io.pedestal.log :as log]
            [mount.core :as mount :refer [defstate]])
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
  (log/info :fn ::start-server! :env/mode env/mode)
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
