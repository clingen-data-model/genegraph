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
                                       ::reference]
                              :opt-un [::accession]
                              :req    [::string]
                              :req-un [::assembly
                                       ::chr
                                       ::end
                                       ::start
                                       ::total_copies]))

(def ^:private the-counts
  "These fields have integer values."
  [:start :end :total_copies])

(def ^:private regular-expressions
  "Order the parsed result keys and their regular expression strings."
  (concat [:assembly            "([^ /]+)"
           ::reference          "([^ /]*)"
           ::cytogenic-location "([^()]*)"
           :chr                 "([^:]+)"]
          (interleave the-counts (repeat "(\\d+)"))))

(def ^:private unparse-template
  "The basic syntax of CNV strings."
  "%s/%s %s(chr%s:%s-%s)x%s")

(def ^:private parse-template
  "Escape the syntax of CNV strings for a regular expression."
  (str/escape unparse-template {\( "\\(" \) "\\)"}))

(def ^:private field-pairs
  "Pair up field keys and their regular expressions."
  (partition 2 regular-expressions))

(def ^:private field-keys
  "Order the keys in the parsed CNV."
  (cons ::string (map first field-pairs)))

(def ^:private the-regular-expression
  "Parse CNV strings with this regular expression."
  (re-pattern (apply (partial format (str "^" parse-template "$"))
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
  {:pre [(string? s)]}
  (let [result (some-> s raw-parse longify-the-counts)]
    (when (s/valid? ::cnv result)
      result)))

(defn unparse
  "Return the string representation of the CNV map."
  [cnv]
  {:pre [(s/valid? ::cnv cnv)]}
  (apply format unparse-template (project cnv)))

(comment
  (def examples ["GRCh37/hg19 1q21.1(chr1:143134063-143284670)x3"
                 "GRCh38/hg38 1p36.33(chr1:1029317-1072906)x4"
                 "GRCh37/hg19 Yp11.32(chrY:21267-39498)x0"
                 "NCBI36/hg18 Xq21.31(chrX:88399122-88520760)x1"
                 "GRCh37/hg19 Xp22.33(chrX:697169-1238257)x0"
                 "GRCh37/hg19 13q31.3(chr13:93244802-93269486)x0"
                 "GRCh38/hg38 6q16.1-16.2(chr6:98770647-99813111)x1"])
  (assert (= examples (mapv (comp unparse parse) examples)))
  "For example ..."
  (s/valid? ::cnv
            {::cytogenetic-location "1q21.1"
             ::reference "hg19"
             ::string "GRCh37/hg19 1q21.1(chr1:143134063-143284670)x3"
             ::variation-id 145208
             :assembly "GRCh37"
             :chr "1"
             :end 143284670
             :start 143134063
             :total_copies 3})
  (s/valid? ::cnv nil))
