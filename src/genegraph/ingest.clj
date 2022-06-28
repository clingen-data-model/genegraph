(ns genegraph.ingest
  "Experiment with ingest ideas."
  (:require [clojure.data       :as data]
            [clojure.data.json  :as json]
            [clojure.java.io    :as io]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [clojure.zip        :as zip]
            [genegraph.debug]))

(defn decode
  "Decode JSON string with stringified `content` field into EDN."
  [json]
  (-> json json/read-str
      (update "content" json/read-str)))

(defn encode
  "Encode EDN as a JSON string with stringified `content` field."
  [edn]
  (-> edn
      (update "content" json/write-str)
      json/write-str))

(def messages
  "Larry gave me these message files."
  ["./canonicaljson/20191105-variation.json"
   "./canonicaljson/20191202-variation.json"])

(defn canonicalize
  "Read IN-FILE, trim whitespace from each line, then write to OUT-FILE."
  [in-file out-file]
  (with-open [in (io/reader in-file)]
    (-> in line-seq
        (->> (map str/trim)
             (apply str)
             (spit out-file)))))

(def canonicalized
  "Write CANONICALIZEd message files to same directory."
  (doseq [in messages]
    (let [path (str/split in #"/")
          leaf (-> path last (str/split #"-") first)
          out  (str/join \/ (conj (vec (butlast path)) (str leaf "-canonical.json")))]
      (canonicalize in out))))

(def was
  "EDN content of canonicalized message file."
  (-> "./canonicaljson/20191105-canonical.json" slurp decode))

(def now
  "EDN content of another canonicalized message file."
  (-> "./canonicaljson/20191202-canonical.json" slurp decode))

(def msg
  "EDN for a shorter message to ease testing."
  {"child_ids" []
   "name" "NM_007294.3(BRCA1):c.4065_4068del (p.Asn1355fs)"
   "content"
   {"FunctionalConsequence"
    {"@Value" "functional variant"
     "XRef" {"@DB" "Sequence Ontology"
             "@ID" "SO:0001536"}}
    "HGVSlist" {"HGVS"
                [{"@Type"
                  "coding"
                  "NucleotideExpression"
                  {"@change"    "c.4065_4068del"
                   "Expression" {"$" "U14680.1:c.4065_4068del"}}}
                 {"@Type"
                  "non-coding"
                  "NucleotideExpression"
                  {"@change"    "n.4184_4187delTCAA"
                   "Expression" {"$" "U14680.1:n.4184_4187delTCAA"}}}]}
    "Location" {"CytogeneticLocation" {"$" "17q21.31"}
                "SequenceLocation"    [{"@display_stop"  "41243483"
                                        "@display_start" "41243480"}
                                       {"@display_stop"  "43091466"
                                        "@display_start" "43091463"}]}
    "OtherNameList" {"Name" [{"$" "3333del4"}
                             {"$" "4184_4187delTCAA"}
                             {"$" "4184del4"}]}
    "XRefList" {"XRef" [{"@DB"   "Breast Cancer Information Core (BIC) (BRCA1)"
                         "@ID"   "4184&base_change=del TCAA"}
                        {"@DB"   "ClinGen", "@ID" "CA026492"}
                        {"@DB"   "OMIM"
                         "@ID"   "113705.0015"
                         "@Type" "Allelic variant"}
                        {"@DB"   "dbSNP"
                         "@ID"   "80357508"
                         "@Type" "rs"}]}}
   "descendant_ids" []
   "protein_change" ["N1355fs" "N1308fs"]})

(defn disorder
  "Return EDN with any vector fields converted to sets."
  [edn]
  (letfn [(branch? [node] (or   (map? node) (vector? node)))
          (entry?  [node] (isa? (type node) clojure.lang.MapEntry))
          (make    [node children]
            (into (if (entry? node) [] (empty node)) children))]
    (loop [loc (zip/zipper branch? seq make edn)]
      (if (zip/end? loc) (zip/root loc)
          (let [node (zip/node loc)]
            (recur (zip/next
                    (if (entry? node)
                      (let [[k v] node]
                        (if (vector? v) (zip/replace loc [k (set v)]) loc))
                      loc))))))))

(defn differ?
  "Nil when LHS equals RHS after DISORDERing their vectors into sets.
  Otherwise return a pair of [only-lhs only-rhs] as in clojure.data/diff."
  [lhs rhs]
  (let [[lonly ronly _both] (apply data/diff (map disorder [lhs rhs]))
        result [lonly ronly]]
    (when-not (every? nil? result) result)))

(assert (disorder msg))
(assert (disorder was))
(assert (disorder now))
(assert (= (disorder was) (disorder now)))
(assert (not (differ? was now)))
(assert (differ? msg now))
(assert (-> (differ? msg now)
            (->> (map (fn [m] (dissoc m "content"))))
            first empty?))
