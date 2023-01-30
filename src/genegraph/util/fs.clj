(ns genegraph.util.fs
  (:require [clojure.java.io :as io])
  (:import (java.io File FileInputStream FileOutputStream)
           (java.util.zip GZIPInputStream GZIPOutputStream)))

(defn ensure-target-directory-exists!
  "Create directory at PATH if it does not already exist.
  Return false if directory cannot be created or already
  exists as something other than a directory."
  [path]
  (let [dir (io/file path)]
    (if (and (.exists dir) (.isDirectory dir))
      true
      (.mkdir dir))))

(defn gzip-file-writer
  "Open FILE-NAME as a writer to a GZIPOutputStream"
  [file-name]
  (-> file-name File. FileOutputStream. GZIPOutputStream. io/writer))

(defn gzip-file-reader
  "Open FILE-NAME as a reader to a GZIPInputStream"
  [file-name]
  (-> file-name File. FileInputStream. GZIPInputStream. io/reader))
