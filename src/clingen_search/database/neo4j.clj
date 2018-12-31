;; (ns clingen-search.database.neo4j
;;   (:require [clojure.java.io :as io]
;;             [mount.core :as mount :refer [defstate]]
;;             [io.pedestal.log :as log]
;;             [clojure.walk :refer [postwalk prewalk]])
;;   (:import [org.neo4j.graphdb.factory GraphDatabaseFactory GraphDatabaseBuilder GraphDatabaseSettings]
;;            [org.neo4j.graphdb Node Relationship Label Transaction GraphDatabaseService]
;;            [org.neo4j.graphdb.schema IndexDefinition Schema]
;;            [java.util HashMap Map List Iterator]))

;; (def db-dir "db")

;; (defn start-neo4j []
;;   (let [bolt (GraphDatabaseSettings/boltConnector "0")]
;;     (-> (GraphDatabaseFactory.) (.newEmbeddedDatabaseBuilder (io/as-file db-dir))
;;         (.setConfig (.type bolt) "BOLT")
;;         (.setConfig (.enabled bolt) "true")
;;         (.setConfig (.address bolt) "0.0.0.0:7687")
;;         .newGraphDatabase)))

;; (declare db)

;; ;; (defstate db 
;; ;;   ;;:start (.newEmbeddedDatabase (GraphDatabaseFactory.) (io/as-file db-dir))
;; ;;   :start (start-neo4j)
;; ;;   :stop (.shutdown db))

;; (defn as-string-keyed-pairs [m]
;;   (into {} (map (fn [i] [(if (keyword? (key i)) (name (key i)) (key i))
;;                          (val i)]) m)))

;; (defn nested-java-hashmap [m]
;;   (let [res (postwalk #(if (map? %) (HashMap. (as-string-keyed-pairs %)) %) m)]
;;     (clojure.pprint/pprint res)
;;     res))

;; (defn as-keyword-keyed-pairs [m]
;;   (map (fn [i] [(keyword (.getKey i)) (.getValue i)])
;;        (-> m .entrySet seq)))

;; (defn clojure-collection [m]
;;   (cond
;;     (instance? Map m) (into {} (as-keyword-keyed-pairs m))
;;     (instance? List m) (into [] m)
;;     (instance? Iterator m) (into [] (iterator-seq m))
;;     :default m))

;; (defn nested-clojure-collection [m]
;;   (prewalk clojure-collection m))

;; (defn query
;;   ([q] (nested-clojure-collection (.execute db q)))
;;   ([q p] (nested-clojure-collection (.execute db q (nested-java-hashmap p)))))

;; (defmacro tx [& body]
;;   `(with-open [transaction# (.beginTx db)]
;;      (let [result# (do ~@body)]
;;        (.success transaction#)
;;        result#)))

;; (defn query-closure [cypher]
;;   (fn 
;;     ([] (tx (query cypher)))
;;     ([params] (tx (query cypher params)))))

;; (defmacro defquery [name cypher]
;;   `(def ~name (query-closure ~cypher)))

;; (defn define-index [label property]
;;   (with-open [tx (.beginTx db)]
;;     (let [schema (.schema db)
;;           index-definition (-> schema (.indexFor (Label/label label))
;;                                (.on property)
;;                                (.create))]
;;       (.success tx))))

;; (defn define-constraint [label property]
;;   (with-open [tx (.beginTx db)]
;;     (let [schema (.schema db)
;;           index-definition (-> schema (.constraintFor (Label/label label))
;;                                (.assertPropertyIsUnique property)
;;                                (.create))]
;;       (.success tx))))

;; (defn delete-all-nodes []
;;   (with-open [tx (.beginTx db)]))

;; (defn create-resource [iri]
;;   (with-open [tx (.beginTx db)]
;;     (let [label (Label/label "Resource")
;;           node (.createNode db (into-array Label [label]))
;;           _ (.setProperty node "iri" iri)]
;;       (.success tx)
;;       node)))

;; (defn find-by-iri [iri]
;;   (with-open [tx (.beginTx db)]
;;     (let [result (.findNodes db (Label/label "Resource") "iri" iri)]
;;       (.success tx)
;;       (into [] (iterator-seq result)))))
