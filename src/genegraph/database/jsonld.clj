(ns genegraph.database.json
  (:require [genegraph.database.query :as q]
            [cheshire.core :as json]
            [camel-snake-kebab.core :as csk]
            [flatland.ordered.map :refer [ordered-map]]))

(defn context [model]
  (let [members (get model [:skos/is-in-scheme :<])
        object-properties (map #(vector (csk/->snake_case (first (:rdfs/label %)))
                                        {"@type" "@id"
                                         "@id" (str %)})
                               (filter #(q/is-rdf-type? % :owl/ObjectProperty) members))
        datatype-properties (map #(vector (csk/->snake_case (first (:rdfs/label %))) (str %))
                                 (filter #(or (q/is-rdf-type? % :owl/DatatypeProperty)
                                              (and (q/is-rdf-type? % :rdfs/Property)
                                                   )) members))
        classes (map #(vector (csk/->PascalCase (first (:rdfs/label %))) (str %))
                     (filter #(q/is-rdf-type? % :owl/Class) members))
        concepts (map #(vector (csk/->PascalCase (first (:rdfs/label %))) (str %))
                     (filter #(q/is-rdf-type? % :skos/Concept) members))]
    (json/generate-string (into (ordered-map) 
                                (concat object-properties datatype-properties classes concepts))
                          {:pretty true})))

