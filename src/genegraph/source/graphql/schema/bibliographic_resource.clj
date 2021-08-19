(ns genegraph.source.graphql.schema.bibliographic-resource
  (:require [genegraph.database.query :as q]))

(defn short-citation [_ _ value]
  (str
   (re-find #"^\w+"(q/ld1-> value [:dc/creator]))
   " "
   (q/ld1-> value [:dc/date])))

(def bibliographic-resource
  {:name :BibliographicResource
   :graphql-type :object
   :description "A book, article, or other documentary resource. (Dublin Core)"
   :implements [:Resource]
   :fields {:short_citation {:type 'String
                             :description "Short-form citation of the reference"
                             :resolve short-citation}}})
