(ns genegraph.auth
  (:require [buddy.sign.jwt :as jwt]
            [buddy.core.certificates :as certs]
            [buddy.core.keys :as buddy-keys :refer [jwk->public-key]]
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import java.util.Base64))

(def google-keys (-> "certificates/google.json"
                     io/resource
                     slurp
                     json/parse-string))

(def google-oath2 (-> "certificates/google-oauth2.json"
                      io/resource
                      slurp
                      (json/parse-string true)))

