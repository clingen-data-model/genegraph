(ns clingen-search.database.instance
  "Maintains the instance of the local database"
  (:require [mount.core :as mount :refer [defstate]])
  (:import [org.apache.jena.tdb2 TDB2Factory]))

(defstate db
  :start (TDB2Factory/connectDataset "tdb") 
  :stop (.close db))
