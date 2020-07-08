(ns genegraph.source.graphql.condition
  (:require [genegraph.database.query :as q :refer [declare-query create-query ld-> ld1->]]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.source.graphql.common.curation :as curation]
            [clojure.string :as str]))

(defresolver gene [args value]
  (q/ld1-> value [:sepio/is-about-gene]))

(defresolver condition-query [args value]
  (q/resource (:iri args)))

(defresolver actionability-curations [args value]
  (q/ld-> value [[:sepio/is-about-condition :<]]))

(defresolver genetic-conditions [args value]
  (curation/curated-genetic-conditions-for-disease {:disease value}))

(defresolver description [args value]
  (q/ld1-> value [:iao/definition]))

(defresolver previous-names [args value]
  )

(defresolver aliases [args value]
  )

(defresolver equivalent-conditions [args value]
  )

(defresolver last-curated-date [args value]
  (let [curation-dates (concat (ld-> value [[:sepio/has-object :<] ;;GENE_VALIDITY
                                            [:sepio/has-subject :<]
                                            :sepio/qualified-contribution
                                            :sepio/activity-date])
                               (ld-> value [[:rdfs/sub-class-of :<] ;; ACTIONABILITY
                                            [:sepio/is-about-condition :<]
                                            :sepio/qualified-contribution
                                            :sepio/activity-date])
                               (ld-> value [:owl/equivalent-class ;; DOSAGE
                                            [:sepio/has-object :<]
                                            [:sepio/has-subject :<]
                                            :sepio/qualified-contribution
                                            :sepio/activity-date]))]
    (->> curation-dates sort last)))

(defresolver curation-activities [args value]
  (curation/activities {:disease value}))


;; DEPRECATED
(defresolver disease-list [args value]
  (let [params (-> args (select-keys [:limit :offset]) (assoc :distinct true))
        selected-curation-type-bgp (case (:curation_type args)
                                     :GENE_VALIDITY curation/gene-validity-bgp
                                     :ACTIONABILITY curation/actionability-bgp
                                     :GENE_DOSAGE curation/gene-dosage-bgp
                                     nil)
        bgp (if (= :ALL (:curation_type args))
              [:union 
               (cons :bgp curation/gene-validity-bgp)
               (cons :bgp curation/actionability-bgp)
               (cons :bgp curation/gene-dosage-bgp)]
              (when (some? selected-curation-type-bgp)
                (cons :bgp selected-curation-type-bgp)))
        query (if (some? bgp)
                (create-query [:project 
                             ['disease]
                               bgp])
                (create-query (str "select ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                                   "<http://purl.obolibrary.org/obo/MONDO_0000001> ."
                                   "FILTER (!isBlank(?s)) }")))]
    (query {::q/params params})))

(defresolver diseases [args value]
  (curation/diseases-for-resolver args value))


(def subclass-of-query 
  (q/create-query 
   (str "select ?s WHERE { ?class "
        " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
        " ?s . "
        " ?s "
        " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
        "<http://purl.obolibrary.org/obo/MONDO_0000001> ."        
        "FILTER (!isBlank(?s)) }")))

(def superclass-of-query
  (q/create-query 
   (str "select ?s WHERE { ?s "
        " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
        " ?class . "
        " ?s "
        " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
        "<http://purl.obolibrary.org/obo/MONDO_0000001> ."
        "FILTER (!isBlank(?s)) }")))

(def direct-subclass-of-query 
  (q/create-query 
   (str "select ?s WHERE { ?class "
        " <http://www.w3.org/2000/01/rdf-schema#subClassOf> "
        " ?s . "
        " ?s "
        " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
        "<http://purl.obolibrary.org/obo/MONDO_0000001> ."        
        "FILTER (!isBlank(?s)) }")))

(def direct-superclass-of-query
  (q/create-query 
   (str "select ?s WHERE { ?s "
        " <http://www.w3.org/2000/01/rdf-schema#subClassOf> "
        " ?class . "
        " ?s "
        " <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
        "<http://purl.obolibrary.org/obo/MONDO_0000001> ."
        "FILTER (!isBlank(?s)) }")))

(defresolver subclasses [args value]
  (superclass-of-query {:class value}))

(defresolver superclasses [args value]
  (subclass-of-query {:class value}))

(defresolver direct-subclasses [args value]
  (direct-superclass-of-query {:class value}))

(defresolver direct-superclasses [args value]
  (direct-subclass-of-query {:class value}))
