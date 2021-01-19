(ns genegraph.source.graphql.group
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.secure :refer [def-role-controlled-resolver]]))


(def groups-query
  (q/create-query "select ?group where { ?group a :foaf/Group }"))

(def-role-controlled-resolver groups
  [:cgagent/genegraph-admin]
  [context args value]
  (groups-query))
