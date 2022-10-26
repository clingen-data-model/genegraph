(ns genegraph.source.graphql.resource
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(defn iri [context args value]
  (or (some-> value (q/ld1-> [:cg/website-legacy-id]) str)
      (str value)))

(defn curie [context args value]
  (q/curie (or (q/ld1-> value [:cg/website-legacy-id])
               value)))

(defresolver label [args resource]
  (first (concat (:skos/preferred-label resource)
                 (:rdfs/label resource)
                 (:foaf/name resource))))

(defresolver website-display-label [args resource]
  (first (concat (:cg/website-display-label resource)
                 (:skos/preferred-label resource)
                 (:rdfs/label resource)
                 (:foaf/name resource))))

(defresolver type [args value]
  (:rdf/type value))

(defresolver alternative-label [args resource]
  (first (:skos/alternative-label resource)))

(defresolver description [args value]
  (first (:dc/description value)))

(defresolver direct-superclasses [args value]
  (q/ld-> value [:rdfs/sub-class-of]))

(defresolver direct-subclasses [args value]
  (q/ld-> value [[:rdfs/sub-class-of :<]]))
