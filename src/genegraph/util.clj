(ns genegraph.util
  (:import (java.io ByteArrayInputStream)))

(defn str->bytestream
  "If s is a string, return an input stream of its contents, else return s"
  [s]
  (if (string? s)
    (-> s .getBytes ByteArrayInputStream.)
    s))

(defn dissoc-ns
  "Dissocs keys from M which have the namespace qualifier NAMESPACE-KW"
  [m namespace-kw]
  (-> (keys m)
      (->> (remove #(= namespace-kw (-> % namespace keyword)))
           (select-keys m))))
