(ns genegraph.env
  (:require [io.pedestal.log :as log]))

(def data-vol (System/getenv "CG_SEARCH_DATA_VOL"))
(def dx-host (System/getenv "DATA_EXCHANGE_HOST"))
(def dx-group (System/getenv "SERVEUR_GROUP"))
(def dx-key-pass (System/getenv "SERVEUR_KEY_PASS"))
(def dx-keystore (System/getenv "SERVEUR_KEYSTORE"))
(def dx-topics (System/getenv "CG_SEARCH_TOPICS"))
(def dx-truststore (System/getenv "SERVEUR_TRUSTSTORE"))


(defn log-environment []
  (log/info :fn :log-environment
            :data-vol data-vol
            :dx-host dx-host
            :dx-group dx-group
            :dx-key-pass (true? dx-key-pass)
            :dx-keystore dx-keystore
            :dx-topics dx-topics))
