(ns genegraph.env
  (:require [io.pedestal.log :as log]))

(def data-vol (System/getenv "CG_SEARCH_DATA_VOL"))
(def dx-host (System/getenv "KAFKA_HOST"))
(def dx-user (System/getenv "KAFKA_USER"))
(def dx-pass (System/getenv "KAFKA_PASS"))
(def dx-topics (System/getenv "CG_SEARCH_TOPICS"))

(defn log-environment []
  (log/info :fn :log-environment
            :data-vol data-vol
            :dx-host dx-host
            :dx-user dx-user
            :dx-pass dx-pass
            :dx-topics dx-topics))

