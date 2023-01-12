(ns genegraph.source.registry.rocks-registry
  (:require [clj-http.client :as http-client]
            [clojure.edn :as edn]
            [genegraph.env :as env]
            [genegraph.rocksdb :as rocksdb]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.log :as log]
            [mount.core :as mount :refer [defstate]]
            [ring.util.request]))

(defn state-is-running? [state-var]
  ((mount/running-states) (str state-var)))

(defn get-key-local [db k]
  (rocksdb/rocks-get db k))

(defn set-key-local [db k v]
  (rocksdb/rocks-put! db k v))

(defn get-key-remote [uri k]
  (let [response (http-client/get (str uri "/key")
                                  {:throw-exceptions false
                                   :query-params {"key" (prn-str k)}})]
    (case (:status response)
      200 (edn/read-string (:body response))
      404 nil
      (throw (ex-info "Error in get-key-remote" {:fn ::get-key-remote
                                                 :uri uri :k k
                                                 :response response})))))

(defn set-key-remote [uri k v]
  (let [response (http-client/post (str uri "/key")
                                   {:throws-exceptions false
                                    :query-params {:key (prn-str k)}
                                    :body (prn-str v)})]
    (case (:status response)
      201 (log/info :fn :set-key-remote :status 201 :key k)
      (throw (ex-info "Error in set-key-remote" {:fn ::set-key-remote
                                                 :uri uri :k k :v v
                                                 :response response})))))

(def rocksdb-name "vrs_registry.db")

(defstate db
  :start (rocksdb/open rocksdb-name)
  :stop (rocksdb/close db))

(def routes
  "Defines a GET and POST handler to db"
  [["/key"
    :get (fn [{:keys [query-params] :as request}]
           (let [key (edn/read-string (:key query-params))]
             (log/debug :fn :key-get
                        :query-params query-params
                        :key key)
             (let [cached-value (rocksdb/rocks-get db key)]
               (log/debug :cached-value cached-value)
               (if (= :genegraph.rocksdb/miss cached-value)
                 {:status 404 :body "Key not found"}
                 {:status 200 :body (prn-str cached-value)}))))
    :route-name ::key-get]

   ["/key"
    :post (fn [request]
            (let [value (-> request ring.util.request/body-string edn/read-string)
                  query-params (:query-params request)
                  key (edn/read-string (:key query-params))
                  #_#_value (edn/read-string (:value query-params))]
              (log/debug :fn :key-post
                         :query-params query-params
                         :key key
                         :value value)
              (rocksdb/rocks-put! db key value)
              {:status 201 :body "Created"}))
    :route-name ::key-post]])

(def rocksdb-http-uri (System/getenv "ROCKSDB_HTTP_URI"))

(defn rocks-http-configured? [] ((comp not nil?) rocksdb-http-uri))

(defn rocks-http-connectable? []
  (try (let [response (http-client/get (str rocksdb-http-uri "/live")
                                       {:throw-exceptions false})]
         (= 200 (:status response)))
       (catch Exception _)))

(def status-routes
  "Compact routes need to be in a set"
  #{["/live"
     :get (fn [_] {:status 200 :body "server is live"})
     :route-name ::liveness]
    ["/ready"
     :get (fn [_] {:status 200 :body "server is ready"})
     :route-name ::readiness]
    ["/env"
     :get (fn [_] {:status 200 :body (str env/environment)})
     :route-name ::env]})

(defn remove-interceptor [service-map interceptor-name]
  (update service-map
          ::http/interceptors
          (fn [interceptors]
            (->> interceptors
                 (filter #(not= interceptor-name (:name %)))
                 (into [])))))

(defonce service-map
  (atom
   (-> {::http/routes status-routes
        ::http/type :jetty
        ::http/port (or (some-> (System/getenv "ROCKSDB_HTTP_PORT") parse-long)
                        6381)
        ;; false to run in background thread
        ::http/join? false}

       ;; Logging all requests is too noisy. Individual handlers can log themselves.
       #_(http/default-interceptors)
       #_(remove-interceptor ::http/log-request))))

(comment
  (-> @service-map http/default-interceptors http/create-server http/start (->> (def testserver)))
  ())

(defn state-restart-if-started [state-var]
  (when (state-is-running? state-var)
    (mount/stop state-var)
    (mount/start state-var)))

(defn- start-server! []
  (log/info :fn ::start-server! :env/mode env/mode :service-map @service-map)
  (http/start (http/create-server @service-map)))

(defstate server
  :start (start-server!)
  :stop (http/stop server))

(defn pedestal-routes-union
  "Returns the union of the COMPACT route definitions in setA
   and setB. Takes the definition from setB if a route with the
   same path and method appears in both."
  [setA setB]
  (log/info :fn :pedestal-routes-union :setA setA :setB setB)
  (assert (every? vector? setA) "Values must be vectors of compact route specifications")
  (assert (every? vector? setB) "Values must be vectors of compact route specifications")
  (letfn [(flatten1 [stuff] (for [b stuff a b] a))
          (keymap [routes]
            (let [expanded-routes (flatten1 (map #(route/expand-routes #{%}) routes))]
              (into {} (map (fn [compact expanded]
                              (vector [(:path expanded) (:method expanded)] compact))
                            routes
                            expanded-routes))))]
    (let [path-method-A (keymap setA)
          path-method-B (keymap setB)]
      (->> (merge path-method-A path-method-B)
           (map second)
           (set)))))

(defn add-routes
  "Add COMPACT route definitions to the service map. If the server is started,
   restart it with the new routes.

   Pedestal uses the collection type as the thing that determines how a
   set of routes is interpreted. Set, List, Vector are all treated differently.
   route/expand-routes should be passed a Set of terse route specs vectors.
   Works:
   (route/expand-routes #{[\"/live\" :get (fn [_] {:status 200}) :route-name :live]})
   Doesn't work:
   (route/expand-routes [[\"/live\" :get (fn [_] {:status 200}) :route-name :live]])
   (route/expand-routes '([\"/live\" :get (fn [_] {:status 200}) :route-name :live]))
   https://github.com/pedestal/pedestal/blob/0.5.10/route/src/io/pedestal/http/route.clj#L399-L410
   If routes are just not expanded, they can be passed to http/create-server as a set.
   "
  [route-defs]
  (log/info :fn :add-routes :route-defs route-defs)
  (swap! service-map assoc ::http/routes (pedestal-routes-union
                                          (::http/routes @service-map)
                                          (set route-defs)))
  (state-restart-if-started #'server))
