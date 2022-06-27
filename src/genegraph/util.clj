(ns genegraph.util
  (:import (java.io ByteArrayInputStream)))

(defn str->bytestream
  "If s is a string, return an input stream of its contents, else return s"
  [s]
  (if (string? s)
    (-> s .getBytes ByteArrayInputStream.)
    s))
