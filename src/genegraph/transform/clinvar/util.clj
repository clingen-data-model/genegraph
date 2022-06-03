(ns genegraph.transform.clinvar.util
  (:require [clojure.spec.alpha :as spec]
            [cheshire.core :as json]
            [io.pedestal.log :as log])
  (:import (java.io ByteArrayInputStream)))

(defn string-is-int?
  "Returns true if input string `s` is an arbitrarily large integer"
  [s]
  (re-matches #"\d+" s))

(defn string-is-int-width?
  "Returns true if input string `s` is an arbitrarily large integer"
  [s width]
  (not (nil? (re-matches (re-pattern (str "\\d{" width "}")) s))))

(defn string-is-yyyy-mm-dd?
  "Returns true if input string `s` is a date with format dddd-dd-dd where d is an int in range [0-9]"
  [s]
  (not (nil? (re-matches (re-pattern "\\d{4}-\\d{2}-\\d{2}") s))))

(defn string-not-empty?
  "Returns true if input string `s` is not empty"
  [s]
  (< 0 (.length s)))

(defn scv-number?
  [s]
  (not (nil? (re-matches #"SCV\d+" s))))

(defn scv-number-versioned?
  [s]
  (not (nil? (re-matches #"SCV[\d.]+" s))))

(defn conditional-join
  "Concatenates the string b to a, adding delim between if not a suffix of a nor a prefix of b"
  [a b delim]
  (if (.endsWith a delim)
    (if (.startsWith b delim)
      (str a (subs b 1))
      (str a b))
    (if (.startsWith b delim)
      (str a b)
      (str a delim b))))

(defn path-join
  "Concatenates the string b to a, adding '/' between if not a suffix of a nor a prefix of b"
  [a b]
  (conditional-join a b "/"))

(defn in? [coll e]
  (some #(= % e) coll))

(defn simplify-dollar-map [m]
  "Return (get m :$) if m is a map and :$ is the only key. Otherwise return m.
  Useful for BigQuery JSON serialization where single values may be turned into $ maps"
  (if (and (map? m)
           (= '(:$) (keys m)))
    (:$ m)
    m))

(defn simplify-dollar-map-recur [m]
  (if (map? m)
    (if (get m :$)
      (simplify-dollar-map-recur (simplify-dollar-map m))
      (into {} (for [[k v] m]
                 [k (simplify-dollar-map-recur v)])))
    (if (coll? m)
      (map simplify-dollar-map-recur m)
      m)))

(defn parse-json-if-not-parsed [val]
  (if (string? val)
    (json/parse-string val)
    val))

(defn parse-nested-content [val]
  (let [nested-content (-> val :content :content parse-json-if-not-parsed simplify-dollar-map-recur)]
    (assoc-in val
              [:content :content]
              nested-content)))

(defn into-sequential-if-not [val]
  (if (not (sequential? val)) [val] val))

(defn string->InputStream [s]
  (ByteArrayInputStream. (.getBytes s)))

;(defmacro log-and-throw- [& args]
;  (let [m (into {} (partition-all 2 args))]
;    (eval (log/error args))
;    (throw (ex-info (str m) m))))

;(defn log-and-throw [& args]
;  (println (str (into '() (partition-all 2 args))))
;  (let [m (into {} (partition-all 2 args))]
;    (eval (log/-error args))
;    (throw (ex-info (str m) m))))
