(ns clingen-search.service
  (:require [hiccup.page :refer [html5]]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [clingen-search.source.html.common :as cg-html]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [clingen-search.source.graphql.core :as gql]
            [clingen-search.source.graphql.gene :as gql-gene]
            [mount.core :refer [defstate]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn about-page
  [request]
  (ring-resp/response (str request (format "Clojure %s - served from %s"
                                           (clojure-version)
                                           (route/url-for ::about-page)))))

(defn home-page
  [request]
  (ring-resp/response (html5 (cg-html/template cg-html/index request))))

(defn resource-page
  [request]
  (ring-resp/response (html5 (cg-html/template 
                              cg-html/resource 
                              (select-keys request [:path-params :query-params])))))




(def hello-schema (schema/compile
                   {:queries {:hello
                              ;; String is quoted here; in EDN the quotation is not required
                              {:type 'String
                               :resolve (constantly "world")}}}))

;; Defines "/" and "/about" routes with their associated :get handlers.
;; The interceptors defined after the verb map (e.g., {:get home-page}
;; apply to / and its children (/about).
(def common-interceptors [(body-params/body-params) http/html-body])

;; Tabular routes
(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/r/:id" :get (conj common-interceptors `resource-page)]
              ["/about" :get (conj common-interceptors `about-page)]})

;; Map-based routes
;; (def routes `{"/" {:interceptors [(body-params/body-params) http/html-body]
;;                   :get home-page
;;                   "/about" {:get about-page}}})

;; Terse/Vector-based routes
;(def routes
;  `[[["/" {:get home-page}
;      ^:interceptors [(body-params/body-params) http/html-body]
;      ["/about" {:get about-page}]]]])


;;(def service (lacinia/service-map (graphql-schema) {:graphiql true}))
(def service (lacinia/service-map gql/schema {:graphiql true}))

;; Consumed by clingen-search.server/create-server
;; See http/default-interceptors for additional options you can configure
;; (def service {:env :prod
;;               ;; You can bring your own non-default interceptors. Make
;;               ;; sure you include routing and set it up right for
;;               ;; dev-mode. If you do, many other keys for configuring
;;               ;; default interceptors will be ignored.
;;               ;; ::http/interceptors []
;;               ::http/routes routes

;;               ;; Uncomment next line to enable CORS support, add
;;               ;; string(s) specifying scheme, host and port for
;;               ;; allowed source(s):
;;               ;;
;;               ;; "http://localhost:8080"
;;               ;;
;;               ;;::http/allowed-origins ["scheme://host:port"]

;;               ;; Tune the Secure Headers
;;               ;; and specifically the Content Security Policy appropriate to your service/application
;;               ;; For more information, see: https://content-security-policy.com/
;;               ;;   See also: https://github.com/pedestal/pedestal/issues/499
;;               ;;::http/secure-headers {:content-security-policy-settings {:object-src "'none'"
;;               ;;                                                          :script-src "'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:"
;;               ;;                                                          :frame-ancestors "'none'"}}

;;               ;; Root for resource interceptor that is available by default.
;;               ::http/resource-path "/public"

;;               ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
;;               ;;  This can also be your own chain provider/server-fn -- http://pedestal.io/reference/architecture-overview#_chain_provider
;;               ::http/type :jetty
;;               ::http/host "0.0.0.0"
;;               ::http/port 8080
;;               ;; Options to pass to the container (Jetty)
;;               ::http/container-options {:h2c? true
;;                                         :h2? false
;;                                         ;:keystore "test/hp/keystore.jks"
;;                                         ;:key-password "password"
;;                                         ;:ssl-port 8443
;;                                         :ssl? false}})

