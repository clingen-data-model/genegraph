(ns clingen-search.database.instance
  "Maintains the instance of the local database"
  (:require [mount.core :as mount :refer [defstate]]
            [clingen-search.env :as env])
  (:import [org.apache.jena.tdb2 TDB2Factory]))

(defstate db
  :start (TDB2Factory/connectDataset (str env/data-vol "tdb")) 
  :stop (.close db))
