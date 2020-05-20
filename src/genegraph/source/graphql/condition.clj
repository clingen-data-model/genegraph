(ns genegraph.source.graphql.condition
  (:require [genegraph.database.query :as q :refer [declare-query create-query ld-> ld1->]]
            [genegraph.source.graphql.common.curation :as curation]
            [clojure.string :as str]))

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

(defn description [context args value]
  (q/ld1-> value [:iao/definition]))

(defn previous-names [context args value]
  )

(defn aliases [context args value]
  )

(defn equivalent-conditions [context args value]
  )

(defn last-curated-date [context args value]
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

(defn curation-activities [context args value]
  (curation/activities {:disease value}))

(defn disease-list [context args value]
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

(defn diseases [context args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        selected-curation-type-bgp (case (:curation_activity args)
                                     :GENE_VALIDITY curation/gene-validity-bgp
                                     :ACTIONABILITY curation/actionability-bgp
                                     :GENE_DOSAGE curation/gene-dosage-disease-bgp
                                     nil)
        bgp (if (= :ALL (:curation_activity args))
              [:union 
               (cons :bgp (conj curation/gene-validity-bgp 
                                '[disease :rdfs/label disease_label]))
               (cons :bgp (conj curation/actionability-bgp
                                '[disease :rdfs/label disease_label]))
               (cons :bgp (conj curation/gene-dosage-disease-bgp
                                '[disease :rdfs/label disease_label]))]
              (when (some? selected-curation-type-bgp)
                (cons :bgp (conj selected-curation-type-bgp
                                 '[disease :rdfs/label disease_label]))))
        query (if (some? bgp)
                (create-query [:project 
                             ['disease]
                               bgp])
                (create-query 
                 (str "select ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                      "<http://purl.obolibrary.org/obo/MONDO_0000001> . "
                      "?s :rdfs/label ?disease_label . "
                      "FILTER (!isBlank(?s)) }")))]
    {:disease_list (query {::q/params params})
     :count (query {::q/params {:type :count}})}))
