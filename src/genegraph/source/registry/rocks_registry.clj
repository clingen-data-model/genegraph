(ns genegraph.source.registry.rocks-registry
  (:require [clj-http.client]
            [clojure.edn :as edn]
            [genegraph.env :as env]
            [genegraph.rocksdb :as rocksdb]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.log :as log]
            [mount.core :as mount :refer [defstate]]
            [ring.util.request]))

(def rocksdb-name "registry.db")

(defstate db
  :start (rocksdb/open rocksdb-name)
  :stop (rocksdb/close db))

(defn state-is-running? [state-var]
  ((mount/running-states) (str state-var)))

(defn destroy!
  "Destroys the rocksdb database. If the state was started, restarts it after destroy."
  []
  (let [started? (state-is-running? #'db)]
    (when started? (mount/stop #'db))
    (rocksdb/rocks-destroy! rocksdb-name)
    (when started? (mount/start #'db))))

(defn get-key-local [k]
  (rocksdb/rocks-get db k))

(defn set-key-local [k v]
  (rocksdb/rocks-put! db k v))

(defn get-key-remote [uri k]
  (let [response (clj-http.client/get (str uri "/key")
                                      {:throw-exceptions false
                                       :query-params {"key" (prn-str k)}})]
    (case (:status response)
      200 (edn/read-string (:body response))
      404 nil
      (throw (ex-info "Error in get-key-remote" {:fn ::get-key-remote
                                                 :uri uri :k k
                                                 :response response})))))

(defn set-key-remote [uri k v]
  (let [response (clj-http.client/post (str uri "/key")
                                       {:throws-exceptions false
                                        :query-params {:key (prn-str k)}
                                        :body (prn-str v)})]
    (case (:status response)
      201 (log/info :fn :set-key-remote :status 201 :key k)
      (throw (ex-info "Error in set-key-remote" {:fn ::set-key-remote
                                                 :uri uri :k k :v v
                                                 :response response})))))

(def status-routes
  [["/live"
    :get (fn [_] {:status 200 :body "server is live"})
    :route-name ::liveness]
   ["/ready"
    :get (fn [_] {:status 200 :body "server is ready"})
    :route-name ::readiness]
   ["/env"
    :get (fn [_] {:status 200 :body (str env/environment)})
    :route-name ::env]])

(def routes
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

(def service-map
  {;; expand-routes requires a set
   ::http/routes (route/expand-routes
                  (into #{} (concat routes status-routes)))
   ::http/type :jetty
   ::http/port (or (some-> (System/getenv "ROCKSDB_HTTP_PORT") parse-long)
                   6381)
   ::http/join? false ;; run in background thread
   })


(defn start-server! []
  (log/info :fn ::start-server! :env/mode env/mode)
  (http/start (http/create-server service-map)))


(defstate server
  :start (start-server!)
  :stop (http/stop server))


;; (mount/stop #'server)
;; (mount/start #'server)
;; (mount/stop #'db)
;; (mount/start #'db)
