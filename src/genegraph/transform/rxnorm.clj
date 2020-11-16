(ns genegraph.transform.rxnorm
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q :refer [resource]]
            [genegraph.transform.types :refer [transform-doc]]
            [io.pedestal.log :as log]))

(defmethod transform-doc :rxnorm [doc-def]
  "Load RXNorm drug data"
  (let [doc-name (:name doc-def)
        ;; redefine :format to be :rdf so we call the proper multi-method
        ;; this will allow us to add the :chebi/drug type triple to the model
        doc-redef (assoc doc-def :format :rdf)
        drug-model (transform-doc doc-redef)
        type-triples (q/construct "CONSTRUCT { ?s a :chebi/Drug } WHERE {?s a :owl/Class }" {} drug-model)]
    (.add drug-model type-triples)))

