(ns genegraph.auth
  (:require [buddy.sign.jwt :as jwt]
            [buddy.core.certificates :as certs]
            [buddy.core.keys :as buddy-keys :refer [jwk->public-key]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :refer [terminate]])
  (:import java.util.Base64
           [com.google.firebase.auth FirebaseAuth FirebaseToken]))

(defn add-authentication [context]
  (if-let [token (get-in context [:connection-params :token])]
    (try
      (let [user (-> (FirebaseAuth/getInstance) (.verifyIdToken token))]
        (if (.isEmailVerified user)
          (assoc context ::user (.getEmail user))
          context))
      (catch Exception e 
        (-> context
            (assoc :exception e)
            terminate)))
    context))

(def auth-interceptor
  (interceptor {:name ::auth
                :enter add-authentication}))
