(ns genegraph.annotate.cnv
  "Parse copy number variation (CNV) syntax."
  (:require [clojure.spec.alpha :as s]
            [clojure.string     :as str]))

(s/def ::string       (s/and string? seq))
(s/def ::accession    ::string)
(s/def ::assembly     ::string)
(s/def ::chr          ::string)
(s/def ::end          nat-int?)
(s/def ::reference    ::string)
(s/def ::start        nat-int?)
(s/def ::total_copies nat-int?)
(s/def ::cnv          (s/keys :opt    [::cytogenetic-location
                                       ::reference
                                       ::string]
                              :opt-un [::accession]
                              :req-un [::assembly
                                       ::chr
                                       ::end
                                       ::start
                                       ::total_copies]))

(s/fdef parse
  :args ::string
  :ret  ::cnv)

(s/fdef unparse
  :args ::cnv
  :ret  ::string)

(def ^:private the-counts
  "These fields have integer values."
  [:start :end :total_copies])

(def ^:private regular-expressions
  "Order the parsed result keys and their regular expression strings."
  (concat [:assembly            "([^\\p{Blank}/]+)"
           ::reference          "([^\\p{Blank}/]*)"
           ::cytogenic-location "([^\\p{Blank}()]*)"
           :chr                 "([^\\p{Blank}:]+)"]
          (interleave the-counts (repeat "(\\d+)"))))

(def ^:private unparse-template
  "The basic syntax of CNV strings."
  "%s/%s %s(chr%s:%s-%s)x%s")

(def ^:private parse-template
  "Escape the syntax of CNV strings for a regular expression."
  (str "^" (str/escape unparse-template {\( "\\(" \) "\\)"}) "$"))

(def ^:private field-pairs
  "Pair up field keys and their regular expressions."
  (partition 2 regular-expressions))

(def ^:private field-keys
  "Order the keys in the parsed CNV."
  (cons ::string (map first field-pairs)))

(def ^:private the-regular-expression
  "Parse CNV strings with this regular expression."
  (re-pattern (apply (partial format parse-template)
                     (map second field-pairs))))

(defn ^:private raw-parse
  "Nil or the CNV string S parsed into a map with FIELD-KEYS."
  [s]
  (let [[match & more] (re-seq the-regular-expression s)]
    (when (and (empty? more)
               (== 8 (count match)))
      (->> match
           (zipmap field-keys)
           (filter (comp seq second))
           (into {})))))

(defn ^:private longify-the-counts
  "Parse the THE-COUNTS into integer values in the RAW-MAP."
  [raw-map]
  (reduce (fn [m k] (update m k parse-long)) raw-map the-counts))

(def ^:private project
  "Project the fields of a CNV map into a sequence."
  (apply juxt (rest field-keys)))

(defn parse
  "Nil or the CNV string S parsed into a map."
  [s]
  (let [result (some-> s raw-parse longify-the-counts)]
    (when (s/valid? ::cnv result)
      result)))

(defn unparse
  "Return the string representation of the CNV map."
  [cnv]
  (apply format unparse-template (project cnv)))
