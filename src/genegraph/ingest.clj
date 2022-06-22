(ns genegraph.ingest
  "Experiment with ingest ideas."
  (:require [clojure.data.json :as json]
            [clojure.zip :as zip]))

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
     "XRef" {"@DB" "Sequence Ontology", "@ID" "SO:0001536"}}
    "HGVSlist" {"HGVS"
                [{"@Type" "coding"
                  "NucleotideExpression"
                  {"@change" "c.4065_4068del"
                   "Expression" {"$" "U14680.1:c.4065_4068del"}}}
                 {"@Type" "non-coding"
                  "NucleotideExpression"
                  {"@change" "n.4184_4187delTCAA"
                   "Expression" {"$" "U14680.1:n.4184_4187delTCAA"}}}]}
    "Location" {"CytogeneticLocation" {"$" "17q21.31"}
                "SequenceLocation" [{"@display_stop" "41243483"
                                     "@display_start" "41243480"}
                                    {"@display_stop" "43091466"
                                     "@display_start" "43091463"}]}
    "OtherNameList" {"Name" [{"$" "3333del4"} {"$" "4184_4187delTCAA"} {"$" "4184del4"}]}
    "XRefList" {"XRef" [{"@DB" "Breast Cancer Information Core (BIC) (BRCA1)"
                         "@ID" "4184&base_change=del TCAA"}
                        {"@DB" "ClinGen", "@ID" "CA026492"}
                        {"@DB" "OMIM", "@ID" "113705.0015", "@Type" "Allelic variant"}
                        {"@DB" "dbSNP", "@ID" "80357508", "@Type" "rs"}]}}
   "descendant_ids" []
   "protein_change" ["N1355fs" "N1308fs"]})

(defn order
  [edn]
  (letfn [(branch? [node] (or (map? node) (vector? node)))
          (make [node children]
            (into (if (isa? (type node) clojure.lang.MapEntry) [] (empty node))
                  children))]
    (let [zipper (clojure.zip/zipper branch? seq make edn)]
      (->> zipper
           (iterate clojure.zip/next)
           (take-while (complement clojure.zip/end?))
           (map clojure.zip/node)))))

(order {:a [0 1] :b [2 3]})
