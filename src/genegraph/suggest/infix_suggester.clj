(ns genegraph.suggest.infix-suggester
  (:require [clojure.string :as str]
            [mount.core :as mount :refer [defstate]]
            [genegraph.env :as env]
            [genegraph.database.instance :refer [db]]
            [genegraph.database.query :as q]
            [genegraph.source.graphql.condition :as condition]
            [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.resource :as resource]
            [genegraph.suggest.serder :as serder]
            [io.pedestal.log :as log])
  (:import [java.io File FileInputStream FileOutputStream]
           [java.net URI]
           [java.nio.file Paths]
           [org.apache.lucene.store FSDirectory]
           [org.apache.lucene.analysis CharArraySet]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.search.suggest Lookup Lookup$LookupResult InputIterator]
           [org.apache.lucene.search.suggest.analyzing AnalyzingInfixSuggester]
           [org.apache.lucene.util BytesRef]))

;; API Docs for AnalyzingInfixSuggester
;; https://lucene.apache.org/core/7_4_0/suggest/org/apache/lucene/search/suggest/analyzing/AnalyzingInfixSuggester.html



(defn create-suggester
  "Create an Lucene AnalyzingInfixSuggester with highlighting enabled"
  ([suggester-path] (create-suggester suggester-path
                                      (StandardAnalyzer.)
                                      (StandardAnalyzer. CharArraySet/EMPTY_SET)))
  ([suggester-path index-analyzer query-analyzer]
   (let [suggester-file (Paths/get (URI. suggester-path))
         suggester-dir (FSDirectory/open suggester-file)
         min-prefix-chars 1
         commit-on-build true
         all-terms-required true
         highlight true
         suggester (AnalyzingInfixSuggester. suggester-dir index-analyzer query-analyzer min-prefix-chars
                                             commit-on-build all-terms-required highlight)]
     suggester)))

(defn initialize [suggester]
  "Initialize the suggester with a null InputIterator"
  ;; seed the suggester with a null iterator - this allows us to call .add to add to the index
  (.build suggester (proxy [InputIterator] [] (next [] nil))))

(defn add-to-suggestions [suggester text payload contexts weight]
  "Add terms and payloads to the suggester index"
  (let [serialized-payload (-> payload serder/serialize BytesRef.)
        serialized-text (BytesRef. (.getBytes text "UTF8"))
        serialized-contexts (into #{} (map #(-> % serder/serialize BytesRef.) contexts))]
    (.add suggester serialized-text serialized-contexts weight serialized-payload)))

(defn commit-suggester [suggester]
  "Commit the suggester index to disk"
  (.commit suggester))

(defn refresh-suggester [suggester]
  "Refreshes the suggester index after additions"
  (.refresh suggester))

(defn close-suggester [suggester]
  "Close the suggester index"
  (.close suggester))

(defn load-suggestions [suggester index-file]
  "Loads the suggestion index from the file system for the suggester."
  (with-open [is (FileInputStream. index-file)]
    (.load suggester is)
    (log/debug :fn :load-suggestions :msg :loaded-index :index-file index-file)))

(defn store-suggestions [suggester index-file]
  "Stores the suggestion index for the cuggester on the file system."
  (with-open [os (FileOutputStream. index-file)]
    (.store suggester os)
    (log/debug :fn :store-suggestions :msg :stored-index :index-file index-file)))

(defn lookup [suggester text contexts num]
  "Perform a suggester lookup"
  (let [serialized-contexts (if (> 0 (count contexts))
                              (into #{} (map #(-> % serder/serialize BytesRef.) contexts))
                              #{})]
    (sort-by str/lower-case (.lookup suggester text serialized-contexts num true true))))

(defn update-suggestion [suggester text payload contexts weight]
  "Update terms and payloads in a suggester index"
  (let [serialized-payload (-> payload serder/serialize BytesRef.)
        serialized-text (BytesRef. (.getBytes text "UTF8"))
        serialized-contexts (into #{} (map #(-> % serder/serialize BytesRef.) contexts))]
    (.update suggester serialized-text serialized-contexts weight serialized-payload)))
