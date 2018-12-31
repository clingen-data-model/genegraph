(ns clingen-search.sink.rdf
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clingen-search.database.neo4j :as db])
  (:import [org.apache.jena.rdf.model Model ModelFactory Literal Resource]
           [org.apache.jena.riot RDFDataMgr RDFLanguages]
           [org.neo4j.graphdb Transaction]
           [java.util HashMap]))

(defn import-resources
  "Import resources defined in the model to neo4j"
  [model]
  (let [subjects (iterator-seq (.listSubjects model))
        objects (filter #(instance? Resource %) (iterator-seq (.listObjects model)))
        iris (map #(vector (.getURI %) (.getLocalName %)) (distinct (concat subjects objects))) 
        batches (partition-all 1000 (remove #(nil? (first %)) iris))]
    (doseq [b batches]
      (db/tx
       (db/query "unwind $iris as iri merge (r:Resource {iri: iri[0]})
                 set r.curie = iri[1]" {"iris" b})))))

(defn import-literals
  "Import literal annotations into neo4j"
  [model]
  (let [statements (iterator-seq (.listStatements model))
        filtered-statements (filter #(->> % .getObject (instance? Literal)) statements)
        properties (reduce (fn [m s]
                             (let [subj (-> s .getSubject .getURI)
                                   pred (-> s .getPredicate .getLocalName)
                                   ;; TODO consider being smarter about data types
                                   ;; just using string rep for now for all
                                   val (-> s .getObject .getLexicalForm)]
                               (assoc m subj (assoc (m subj {}) pred val))))
                           {} filtered-statements)
        batches (partition-all 1000 properties)]
    (doseq [b batches]
      (db/tx
        (db/query "unwind $props as p match (r:Resource {iri: p[0]}) set r += p[1]"
              {"props" b})))))

(defn resource-statements
  "return a lazy-seq of resource-to-resource statements in the given statement list"
  [statements]
  (let [filtered (filter #(->> % .getObject (instance? Resource)) statements)
        stmts (map (fn [n]  (HashMap. {"subject" (-> n .getSubject .getURI)
                                       "predicate" (-> n .getPredicate .getLocalName)
                                       "object" (-> n .getObject .getURI)}))
                  filtered)]
    (into [] (filter #(and (get % "subject") (get % "object")) stmts))))

(defn import-relationships
  "import the specified relationships  into neo4j. Import all relationships if 
  types is not defined, otherwise import only the types specified"
  [model]
  (let [statements (resource-statements (iterator-seq (.listStatements model)))
        batched-statements (partition-all 1000 statements)]
    (doseq [s batched-statements]
      (let [grouped-statements (group-by #(get % "predicate") s)]
        (doseq [[predicate group] grouped-statements]
          (db/tx
            (let [query (str "UNWIND $statements AS stmt "
                             "MATCH (s:Resource {iri: stmt.subject}) "
                             "MATCH (o:Resource {iri: stmt.object}) "
                             "MERGE (s)-[:" predicate "]->(o)")]
              (db/query query {"statements" group}))))))))

(defn detect-syntax
  "Detect the RDF syntax based on the file extension"
  [filename]
  (let [ext (re-find #"\.\w+$" filename)]
    (case ext
      ".xml" "RDF/XML"
      ".ttl" "TURTLE"
      ".owl" "RDF/XML"
      ".jsonld" "JSON-LD")))

(defn import-rdf
  "Import RDF data as-is into Neo4j"
  [filename]
  (with-open [f (io/input-stream filename)
              m (ModelFactory/createDefaultModel)]
    ;; TODO detect serialization
    (.read m f nil (detect-syntax filename))
    (println "Importing resources from " filename)
    (import-resources m)
    (println "Importing relationships from " filename)
    (import-relationships m)
    (println "Importing literals from " filename)
    (import-literals m)))
