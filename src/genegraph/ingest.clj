(ns genegraph.ingest
  "Experiment with ingest ideas."
  (:require [clojure.data      :as data]
            [clojure.data.json :as json]
            [clojure.zip       :as zip]
            [genegraph.debug]))

(defn decode
  "Decode JSON file with stringified `content` field into EDN."
  [file]
  (-> file slurp json/read-str
      (update "content" json/read-str)))

(def was (decode "./canonicaljson/20191105-variation.json"))
(def now (decode "./canonicaljson/20191202-variation.json"))
(def msg
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
  Otherwise return a pair of [only-in-lhs only-in-rhs] as in clojure.data/diff."
  [lhs rhs]
  (let [[lonly ronly both] (apply data/diff (map disorder [lhs rhs]))]
    (when-not (every? nil? [lonly ronly])
      [lonly ronly])))

(assert (disorder msg))
(assert (disorder was))
(assert (disorder now))
(assert (= (disorder was) (disorder now)))
(assert (not (differ? was now)))
(assert (differ? msg now))