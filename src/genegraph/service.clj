(ns genegraph.service
  (:require [hiccup.page :refer [html5]]
            [io.pedestal.interceptor :as pedestal-interceptor]
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
            [genegraph.auth :as auth]
            [genegraph.source.graphql.gene :as gql-gene]
            [genegraph.response-cache :refer [response-cache-interceptor]]
            [genegraph.env :as env]
            [mount.core :refer [defstate]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [genegraph.database.util :refer [begin-read-tx close-read-tx]]
            [io.pedestal.log :as log]))

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

(defn dev-interceptors []
  (-> (lacinia-pedestal/default-interceptors gql/schema {})
      (lacinia/inject nil :replace ::lacinia-pedestal/body-data)
      (lacinia/inject lacinia-pedestal/body-data-interceptor
                      :before
                      ::lacinia-pedestal/json-response)
      (lacinia/inject open-tx-interceptor
                      :before
                      ::lacinia-pedestal/query-executor)
      (lacinia/inject log-request-interceptor
                      :after
                      ::lacinia-pedestal/body-data)
      (lacinia/inject auth/auth-interceptor
                      :before
                      ::lacinia-pedestal/body-data)
      (lacinia/inject user-info-interceptor
                      :after
                      ::lacinia-pedestal/inject-app-context)))

(defn prod-interceptors []
  (let [interceptor-chain
        (-> (lacinia-pedestal/default-interceptors (gql/schema) {})
            (lacinia/inject nil :replace ::lacinia-pedestal/body-data)
            (lacinia/inject nil :replace ::lacinia-pedestal/enable-tracing)
            (lacinia/inject lacinia-pedestal/body-data-interceptor
                            :before
                            ::lacinia-pedestal/json-response)
            (lacinia/inject (pedestal-interceptor/interceptor open-tx-interceptor)
                            :before
                            ::lacinia-pedestal/query-executor)
            (lacinia/inject auth/auth-interceptor
                            :before
                            ::lacinia-pedestal/body-data)
            (lacinia/inject user-info-interceptor
                            :after
                            ::lacinia-pedestal/inject-app-context))]
    (cond-> interceptor-chain
      env/use-response-cache (lacinia/inject 
                              (pedestal-interceptor/interceptor
                               (response-cache-interceptor))
                              :after
                              ::lacinia-pedestal/body-data))))

(defn dev-subscription-interceptors []
  (-> (lacinia-subs/default-subscription-interceptors gql/schema {})
      (lacinia/inject (pedestal-interceptor/interceptor open-tx-interceptor)
                      :before
                      ::lacinia-subs/execute-operation)
      (lacinia/inject (pedestal-interceptor/interceptor log-request-interceptor)
                      :before
                      ::lacinia-subs/exception-handler)
      (lacinia/inject auth/auth-interceptor
                      :after
                      ::lacinia-subs/exception-handler)
      (lacinia/inject user-info-interceptor
                      :after
                      ::lacinia-subs/inject-app-context)))

(defn prod-subscription-interceptors []
  (let [interceptor-chain 
        (-> (lacinia-subs/default-subscription-interceptors (gql/schema) {})
            (lacinia/inject (pedestal-interceptor/interceptor open-tx-interceptor)
                            :before
                            ::lacinia-subs/execute-operation)
            (lacinia/inject auth/auth-interceptor
                            :after
                            ::lacinia-subs/exception-handler)
            (lacinia/inject user-info-interceptor
                            :after
                            ::lacinia-subs/inject-app-context))]
    (cond-> interceptor-chain
      env/use-response-cache (lacinia/inject 
                              (pedestal-interceptor/interceptor
                               (response-cache-interceptor))
                              :before
                              ::lacinia-subs/send-operation-response))))

(defn service-map [interceptors subscription-interceptors]
  (-> {:env :dev,
       ::http/host "0.0.0.0"
       :io.pedestal.http/routes
       (set/union
        (lacinia-pedestal/graphiql-asset-routes "/assets/graphiql")
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
           :com.walmartlabs.lacinia.pedestal2/graphiql-ide]}),
       :io.pedestal.http/port 8888,
       :io.pedestal.http/type :jetty,
       :io.pedestal.http/join? false,
       :io.pedestal.http/secure-headers nil}
      lacinia-pedestal/enable-graphiql
      (lacinia-pedestal/enable-subscriptions 
       gql/schema 
       {:subscription-interceptors subscription-interceptors})))

(defn dev-service 
  "Service map to be used for development mode."
  []
  (service-map (dev-interceptors) (dev-subscription-interceptors)))

(defn service []
  (service-map (prod-interceptors) (prod-subscription-interceptors)))

