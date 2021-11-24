(ns genegraph.service
  (:require [hiccup.page :refer [html5]]
            [io.pedestal.interceptor :as pedestal-interceptor]
            [io.pedestal.interceptor.chain :as pedestal-chain]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [genegraph.source.html.common :as cg-html]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.pedestal2 :as lacinia-pedestal]
            [com.walmartlabs.lacinia.pedestal.subscriptions :as lacinia-subs]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [genegraph.source.graphql.core :as gql]
            [genegraph.source.graphql.experimental-schema :as experimental-schema]
            [genegraph.auth :as auth]
            [genegraph.response-cache :refer [response-cache-interceptor]]
            [genegraph.env :as env]
            [mount.core :refer [defstate]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [genegraph.database.util :refer [begin-read-tx close-read-tx]]
            [io.pedestal.log :as log])
  (:import java.net.InetAddress
           (javax.servlet.http HttpServletRequest HttpServletResponse)))


;; Currently just makes sure :error doesn't reach Pedestal
;; More sophisticated handling to follow
(def error-reporting-interceptor
  (pedestal-interceptor/interceptor
   {:name ::error-reporting
    :error (fn [context] (dissoc context :error))}))

(def open-tx-interceptor
  {:name ::open-tx
   :enter (fn [context] (begin-read-tx) context)
   :leave (fn [context] (close-read-tx) context)})

(def log-request-interceptor
  {:name ::log-request
   :enter (fn [context] (log/info :request context) context)})

(def user-info-interceptor
  (pedestal-interceptor/interceptor
   {:name ::user-info
    :enter (fn [context]
             (let [user-info (select-keys context [::auth/user ::auth/roles])]
               (assoc-in context
                         [:request :lacinia-app-context]
                         (merge (get-in context [:request :lacinia-app-context])
                                user-info))))}))


(def current-requests (atom {}))

(def request-gate-interceptor
  (pedestal-interceptor/interceptor
   {:name ::request-gate
    ;; If this is the first request of its kind, leave a promise
    ;; associated with the request, to be satisfied when the request returns
    :enter (fn [context]
             ;; (log/info :fn ::request-gate :msg "In interceptor enter")
             (let [body (get-in context [:request :body])
                   requests
                   (swap! current-requests
                          (fn [requests cx]
                            (let [body (get-in cx [:request :body])]
                              (if (get requests body)
                                requests
                                (assoc requests body
                                       (assoc cx ::promise (promise))))))
                          context)]
               (if (= (::pedestal-chain/execution-id context)
                      (get-in requests [body ::pedestal-chain/execution-id]))
                 context
                 (-> context
                     (assoc :response (-> requests
                                          (get-in [body ::promise])
                                          deref))
                     pedestal-chain/terminate))))
    ;; Deliver the promise, if first request, and return.
    :leave (fn [context]
             (let [body (get-in context [:request :body])
                   first-request (get @current-requests body)]
               (when (= (::pedestal-chain/execution-id context)
                        (::pedestal-chain/execution-id first-request))
                 (deliver (::promise first-request) (:response context))
                 (swap! current-requests dissoc body))
               context))}))

(def host (InetAddress/getLocalHost))

(def request-logging-interceptor
  (pedestal-interceptor/interceptor
   {:name ::request-logging-interceptor
    :enter (fn [ctx]
             (assoc-in ctx [:request :start-time] (System/currentTimeMillis)))
    :leave (fn [ctx]
             (let [{:keys [uri start-time request-method]} (:request ctx)
                   finish (System/currentTimeMillis)
                   total (- finish start-time)]
               (log/info :fn :request-logging-interceptor
                            :uri uri
                            :request-method (str/upper-case request-method)
                            :hostname (.getHostName host) 
                            :request-ip-addr (get-in ctx [:request :remote-addr])
                            :servlet-request (.toString (.getRequestURL (get-in ctx [:request :servlet-request])))
                            :servlet-request-body (get-in ctx [:request :body])
                            :reponse-status (.getStatus (get-in ctx [:request :servlet-response]))
                            :response-time (str total "ms"))
               ctx))}))
    
(defn dev-interceptors [gql-schema]
  (-> (lacinia-pedestal/default-interceptors gql-schema {})
      (lacinia/inject nil :replace ::lacinia-pedestal/body-data)
      ;; (lacinia/inject nil :replace ::lacinia-pedestal/enable-tracing)
      (lacinia/inject lacinia-pedestal/body-data-interceptor
                      :before
                      ::lacinia-pedestal/json-response)
      (lacinia/inject open-tx-interceptor
                      :before
                      ::lacinia-pedestal/query-executor)
      (lacinia/inject log-request-interceptor
                      :after
                      ::lacinia-pedestal/body-data)
      ;; (lacinia/inject auth/auth-interceptor
      ;;                 :before
      ;;                 ::lacinia-pedestal/body-data)
      (lacinia/inject request-gate-interceptor
                      :after
                      ::lacinia-pedestal/body-data)
      (lacinia/inject error-reporting-interceptor
                      :before
                      ::lacinia-pedestal/body-data)
      ;; (lacinia/inject user-info-interceptor
      ;;                 :after
      ;;                 ::lacinia-pedestal/inject-app-context)
      ))

(defn prod-interceptors [gql-schema]
  (let [interceptor-chain
        (-> (lacinia-pedestal/default-interceptors gql-schema {})
            (lacinia/inject nil :replace ::lacinia-pedestal/body-data)
            (lacinia/inject nil :replace ::lacinia-pedestal/enable-tracing)
            (lacinia/inject lacinia-pedestal/body-data-interceptor
                            :before
                            ::lacinia-pedestal/json-response)
            (lacinia/inject (pedestal-interceptor/interceptor open-tx-interceptor)
                            :before
                            ::lacinia-pedestal/query-executor)
            ;; (lacinia/inject auth/auth-interceptor
            ;;                 :before
            ;;                 ::lacinia-pedestal/body-data)
            (lacinia/inject request-gate-interceptor
                            :after
                            ::lacinia-pedestal/body-data)
            ;; (lacinia/inject user-info-interceptor
            ;;                 :after
            ;;                 ::lacinia-pedestal/inject-app-context)
            (lacinia/inject request-logging-interceptor
                      :before
                      ::lacinia-pedestal/initialize-tracing)
            (lacinia/inject error-reporting-interceptor
                            :before
                            ::lacinia-pedestal/body-data))]
    (cond-> interceptor-chain
      env/use-response-cache (lacinia/inject 
                              (pedestal-interceptor/interceptor
                               (response-cache-interceptor))
                              :after
                              ::lacinia-pedestal/body-data))))

(defn dev-subscription-interceptors [gql-schema]
  (-> (lacinia-subs/default-subscription-interceptors gql-schema {})
      (lacinia/inject request-gate-interceptor
                      :before
                      ::lacinia-subs/execute-operation)
      (lacinia/inject (pedestal-interceptor/interceptor open-tx-interceptor)
                      :before
                      ::lacinia-subs/execute-operation)
      (lacinia/inject (pedestal-interceptor/interceptor log-request-interceptor)
                      :before
                      ::lacinia-subs/exception-handler)
      (lacinia/inject error-reporting-interceptor
                      :before
                      ::lacinia-subs/exception-handler)
      ;; (lacinia/inject auth/auth-interceptor
      ;;                 :after
      ;;                 ::lacinia-subs/exception-handler)
      ;; (lacinia/inject user-info-interceptor
      ;;                 :after
      ;;                 ::lacinia-subs/inject-app-context)
      ))

(defn prod-subscription-interceptors [gql-schema]
  (let [interceptor-chain 
        (-> (lacinia-subs/default-subscription-interceptors gql-schema {})
            (lacinia/inject request-gate-interceptor
                            :before
                            ::lacinia-subs/execute-operation)
            (lacinia/inject (pedestal-interceptor/interceptor open-tx-interceptor)
                            :before
                            ::lacinia-subs/execute-operation)
            (lacinia/inject error-reporting-interceptor
                            :before
                            ::lacinia-subs/exception-handler)
            ;; (lacinia/inject auth/auth-interceptor
            ;;                 :after
            ;;                 ::lacinia-subs/exception-handler)
            ;; (lacinia/inject user-info-interceptor
            ;;                 :after
            ;;                 ::lacinia-subs/inject-app-context)
            )]
    (cond-> interceptor-chain
      env/use-response-cache (lacinia/inject 
                              (pedestal-interceptor/interceptor
                               (response-cache-interceptor))
                              :before
                              ::lacinia-subs/send-operation-response))))

(def base-service-map
  {:env :dev
   ::http/host "0.0.0.0"
   ::http/allowed-origins {:allowed-origins (constantly true)
                           :creds true}
   :io.pedestal.http/routes #{}
   :io.pedestal.http/port 8888,
   :io.pedestal.http/type :jetty,
   :io.pedestal.http/join? false,
   :io.pedestal.http/secure-headers nil})

(defn graphql-routes [interceptors]
  (set/union(lacinia-pedestal/graphiql-asset-routes "/assets/graphiql")
            #{["/api"
               :post interceptors
               :route-name
               :com.walmartlabs.lacinia.pedestal2/graphql-api]
              ["/graphql"
               :post interceptors
               :route-name
               ::legacy-api-route]
              ["/ide"
               :get (lacinia-pedestal/graphiql-ide-handler {})
               :route-name
               :com.walmartlabs.lacinia.pedestal2/graphiql-ide]}))

(defn service-map [interceptors subscription-interceptors gql-schema]
  (-> base-service-map
      (assoc :io.pedestal.http/routes (graphql-routes interceptors))
      lacinia-pedestal/enable-graphiql
      (lacinia-pedestal/enable-subscriptions 
       gql-schema
       {:subscription-interceptors subscription-interceptors})))

(defn transformer-service []
  base-service-map)

(defn dev-service 
  "Service map to be used for development mode."
  ([] (dev-service (if env/use-experimental-schema
                     experimental-schema/merged-schema
                     gql/schema)))
  ([gql-schema]
   (service-map (dev-interceptors gql-schema)
                (dev-subscription-interceptors gql-schema)
                gql-schema)))

(defn prod-service
  "Service map to be used for production mode"
  ([] (service (if env/use-experimental-schema
                 (experimental-schema/merged-schema)
                 (gql/schema))))
  ([gql-schema]
   (service-map (prod-interceptors gql-schema)
                (prod-subscription-interceptors gql-schema)
                gql-schema)))

