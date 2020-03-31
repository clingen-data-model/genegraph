(ns genegraph.database.instance
  "Maintains the instance of the local database"
  (:require [mount.core :as mount :refer [defstate]]
            [genegraph.env :as env])
  (:import [org.apache.jena.query.text TextDatasetFactory]))

(defstate db
  :start (TextDatasetFactory/create "resources/genegraph-assembly.ttl")
  :stop (.close db))
