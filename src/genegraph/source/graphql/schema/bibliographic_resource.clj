(ns genegraph.source.graphql.schema.bibliographic-resource
  (:require [genegraph.database.query :as q]))

(defn short-citation [_ _ value]
  (if-let [creator (q/ld1-> value [:dc/creator])]
    (str
     (second (re-find #"^(.*)\W(\w+)$" creator))
     " "
     (q/ld1-> value [:dc/date]))))

(def bibliographic-resource
  {:name :BibliographicResource
   :graphql-type :object
   :description "A book, article, or other documentary resource. (Dublin Core)"
   :implements [:Resource]
   :fields {:short_citation {:type 'String
                             :description "Short-form citation of the reference"
                             :resolve short-citation}
            :multiple_authors {:type 'Boolean
                               :description "Boolean indicating if there are multiple authors"
                               :path [:sepio/multiple-authors]}
            :first_author {:type 'String
                           :description "First author"
                           :path [:dc/creator]}
            :year_published {:type 'String
                             :description "The year the paper was published"
                             :path [:dc/date]}
            :abstract {:type 'String
                       :description "The paper's abstract."
                       :path [:dc/abstract]}}})
            
