(ns genegraph.source.graphql.condition
  (:require [genegraph.database.query :as q]))

;; TODO, this needs to be defined in terms of a consistent data model in future,
;; but for the time being it is too complicated to allow blank nodes to be represented
;; for conditions, using parent IRI in case of blank node
;; TODO, but also in a different area of the code, should define test for blank node in query.clj
(defn iri [context args value]
  (if (str value)
    (str value)
    (str (q/ld1-> value [:rdfs/sub-class-of]))))

(defn label [context args value]
  (if (str value)
    (q/ld1-> value [:rdfs/label])
    (q/ld1-> value [:rdfs/sub-class-of :rdfs/label])))

(defn gene [context args value]
  (q/ld1-> value [:sepio/is-about-gene]))

(defn condition-query [context args value]
  (q/resource (:iri args)))

(defn actionability-curations [context args value]
  (q/ld-> value [[:sepio/is-about-condition :<]]))

(defn genetic-conditions [context args value]
  (if (q/is-rdf-type? value :sepio/GeneticCondition)
    (let [g (q/ld1-> value [:sepio/is-about-gene])]
      (filter #(and (q/is-rdf-type? % :sepio/GeneticCondition)
                    (= (q/ld1-> % [:sepio/is-about-gene]) g))
              (q/ld-> value [[:rdfs/sub-class-of :<]])))
    (->> (q/ld-> value [[:rdfs/sub-class-of :<]])
         (filter #(q/is-rdf-type? % :sepio/GeneticCondition)))))
