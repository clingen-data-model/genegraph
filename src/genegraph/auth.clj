(ns genegraph.auth
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :refer [terminate]]
            [genegraph.database.query :as q])
  (:import java.util.Base64
           [com.google.firebase.auth FirebaseAuth FirebaseToken]))

(def find-user-by-email-query
  (q/create-query "select ?user where { ?user :foaf/mbox ?email }"))

(defn find-user-by-email [email]
  (->> (find-user-by-email-query
        {:email (q/resource (str "mailto:" email))})
       first))

(defn add-authentication [context]
  (if-let [token (get-in context [:connection-params :token])]
    (try
      (let [user (-> (FirebaseAuth/getInstance) (.verifyIdToken token))]
        (if (.isEmailVerified user)
          (let [user-resource (find-user-by-email (.getEmail user))]
            (assoc context ::user user-resource
                           ::roles (into #{} (:foaf/member user-resource))))
          context))
      (catch Exception e 
        (-> context
            (assoc :exception e)
            terminate)))
    context))

(def auth-interceptor
  (interceptor {:name ::auth
                :enter add-authentication}))
