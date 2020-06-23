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

;; (defresolver genetic-conditions [args value]
;;   (if (q/is-rdf-type? value :sepio/GeneticCondition)
;;     (let [g (q/ld1-> value [:sepio/is-about-gene])]
;;       (filter #(and (q/is-rdf-type? % :sepio/GeneticCondition)
;;                     (= (q/ld1-> % [:sepio/is-about-gene]) g))
;;               (q/ld-> value [[:rdfs/sub-class-of :<]])))
;;     (->> (q/ld-> value [[:rdfs/sub-class-of :<]])
;;          (filter #(q/is-rdf-type? % :sepio/GeneticCondition)))))

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
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (if (:text args)
                       {:text (-> args :text str/lower-case) ::q/params params}
                       {::q/params params})
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
        query-bgp (if (:text args) 
                    [:join (cons :bgp (q/text-search-bgp 'disease :cg/resource 'text)) bgp]
                    bgp)
        query (if (some? bgp)
                (create-query [:project 
                             ['disease]
                               query-bgp])
                ;; Consider restructuring this around a BGP when variable length
                ;; predicates are supported in the algebra, is messy as written.
                (if (:text args)
                  (create-query 
                   (str "select ?s WHERE { "
                        "?s :jena/query ( :cg/resource ?text ) . "
                        "?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                        "<http://purl.obolibrary.org/obo/MONDO_0000001> . "
                        "?s :rdfs/label ?disease_label . "
                        "FILTER (!isBlank(?s)) }"))
                  (create-query 
                   (str "select ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                        "<http://purl.obolibrary.org/obo/MONDO_0000001> . "
                        "?s :rdfs/label ?disease_label . "
                        "FILTER (!isBlank(?s)) }"))))]
    {:disease_list (query query-params)
     :count (query (assoc query-params ::q/params {:type :count :distinct true}))}))
