(ns genegraph.suggest.suggesters
  (:require [mount.core :as mount :refer [defstate]]
            [genegraph.env :as env]
            [genegraph.database.query :as q]
            [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.resource :as resource]
            [genegraph.source.graphql.drug :as drug]
            [genegraph.suggest.serder :as serder]
            [genegraph.suggest.infix-suggester :as suggest]
            [io.pedestal.log :as log]))

(def suggesters (atom {}))

(def disease-query (q/create-query (str "select ?s WHERE "
                                        "{ ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                                        "<http://purl.obolibrary.org/obo/MONDO_0000001> ."
                                        "FILTER (!isBlank(?s)) }")))

(defn disease-payload [disease]
  "Create a disease suggester payload"
  (let [iri (resource/iri nil nil disease)
        label (resource/label nil nil disease)
        curie (resource/curie nil nil disease)
        curations (curation/activities {:disease disease})
        weight (count curations)
        payload {:type :DISEASE
                 :iri iri
                 :label label
                 :curie curie
                 :curations curations
                 :weight weight}]
    (log/info :fn :disease-payload :msg "disease payload generated" :payload payload)
    payload))

(def gene-query (q/create-query (str "select ?s WHERE { ?s a :so/ProteinCodingGene }")))

(defn gene-payload [gene]
  "Create a gene suggester payload"
  (let [iri (resource/iri nil nil gene)
        label (resource/label nil nil gene)
        curie (resource/curie nil nil gene)
        curations (curation/activities {:gene gene})
        weight (count curations)
        payload {:type :GENE
                 :iri iri
                 :label label
                 :curie curie
                 :curations curations
                 :weight weight}]
    (log/info :fn :gene-payload :msg "gene payload generated" :payload payload)
    payload))

(def drug-query (q/create-query (str "select ?s WHERE { ?s a :chebi/Drug }")))

(defn drug-payload [drug]
  "Create a drug suggester payload"
  (let [iri (resource/iri nil nil drug)
        label (drug/label nil nil drug)
        curie (resource/curie nil nil drug)
        curations #{}
        weight 0
        payload {:type :DRUG
                 :iri iri
                 :label label
                 :curie curie
                 :curations curations
                 :weight weight}]
    (log/info :fn :drug-payload :msg "drug payload generated" :payload payload)
    payload))

(def suggesters-map {:disease {:dirpath (str "file://" env/data-vol "/suggestions/diseases")
                               :query disease-query
                               :payload disease-payload
                               }
                     :gene {:dirpath (str "file://" env/data-vol "/suggestions/genes")
                            :query gene-query
                            :payload gene-payload
                            }
                     :drug {:dirpath (str "file://" env/data-vol "/suggestions/drugs")
                            :query drug-query
                            :payload drug-payload
                            }
                     })

(defn create-suggesters []
  "Create suggesters from configuration and store in an atom"
  (let [sugg-map (reduce (fn [coll key]
                              (let [map (get suggesters-map key)
                                    suggester (suggest/create-suggester (:dirpath map))]
                                (assoc coll key suggester)))
                            {}
                            (keys suggesters-map))]
  (reset! suggesters sugg-map)))

(defn close-suggesters []
  "Close all configured suggesters"
  (doseq [suggester (vals @suggesters)]
    (suggest/close-suggester suggester)))

(defn build-suggestions [key]
  "Run a database query from which to build a suggester index, and persist it"
  (log/info :fn :build-suggestions :suggester key :msg :start)
  (let [map (get suggesters-map key)
        suggester (get @suggesters key)
        query (:query map)
        payload-fn (:payload map)]
    (suggest/initialize suggester)
    (doseq [row (query)]
      (let [payload (payload-fn row)]
        (suggest/add-to-suggestions suggester
                                    (:label payload)
                                    payload
                                    (:curations payload)
                                    (:weight payload))))
    (suggest/commit-suggester suggester)
    (suggest/refresh-suggester suggester)
    (log/info :fn :build-suggestions :suggester key :msg :complete)))
                                    
(defn build-all-suggestions []
  "Build all of the suggester indices for all configured suggesters"
  (doseq [suggester-key (keys @suggesters)]
    (build-suggestions suggester-key)))

(defn get-suggester [key]
  "Retreive a suggester from the suggester atom" 
  (get @suggesters key))

(defn lookup [suggester-key text contexts num]
  "Lookup a term using a suggester"
  (log/info :fn :lookup :suggester suggester-key :text text :contexts contexts :num num)
  (suggest/lookup (get-suggester suggester-key) text contexts num))

(defstate suggestions
  :start (create-suggesters)
  :stop (close-suggesters))
