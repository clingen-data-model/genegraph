(ns clingen-search.env)

(def data-vol (System/getenv "CG_SEARCH_DATA_VOL"))
(def dx-host (System/getenv "DATA_EXCHANGE_HOST"))
(def dx-group (System/getenv "SERVEUR_GROUP"))
(def dx-key-pass (System/getenv "SERVEUR_KEY_PASS"))
(def dx-keystore (System/getenv "SERVEUR_KEYSTORE"))
(def dx-topics (System/getenv "CG_SEARCH_TOPICS"))
