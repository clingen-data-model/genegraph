(ns genegraph.source.graphql.user
  (:require [genegraph.source.graphql.common.secure :refer [def-role-controlled-resolver]]
            [genegraph.database.query :as q]))

(def find-user-by-email-query
  (q/create-query "select ?user where { ?user :foaf/mbox ?email }"))

(defn find-user-by-email [email]
  (->> (find-user-by-email-query
        {:email (q/resource (str "mailto:" email))})
       first))

(defn email [context args value]
  (some->> (q/ld1-> value [:foaf/mbox])
           str
           (re-find #"mailto:(.*)")
           second))

(def-role-controlled-resolver user-query
  [:cgagent/genegraph-admin]
  [context args value]
  (find-user-by-email (:email args)))

(def-role-controlled-resolver member-of
  [:cgagent/genegraph-admin]
  [context args value]
  (:foaf/member value))

(defn current-user [context args value]
  (:genegraph.auth/user context))

(defn is-admin [context args value]
  (some #(= (q/resource :cgagent/genegraph-admin) %)
        (:foaf/member value)))
