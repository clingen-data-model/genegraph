(ns genegraph.database.admin
  (:require [genegraph.database.names :as names]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]))


(defn- update-name-set [names target-file]
  (with-open [os (io/writer target-file)]
    (binding [*out* os
              *print-length* nil]
      (pr names))))

(defn update-names []
  (update-name-set (names/object-properties) "resources/property-names.edn")
  (update-name-set (names/class-names) "resources/class-names.edn"))
