(ns genegraph.util.fs
  (:require [clojure.java.io :as io])
  (:import [java.io File]))

(defn ensure-target-directory-exists!
  "Create directory at PATH if it does not already exist.
  Return false if directory cannot be created or already
  exists as something other than a directory."
  [path]
  (let [dir (io/file path)]
    (if (and (.exists dir) (.isDirectory dir))
      true
      (.mkdir dir))))
