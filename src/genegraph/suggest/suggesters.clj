(ns genegraph.suggest.suggesters
  (:require [mount.core :as mount :refer [defstate]]
            [genegraph.env :as env]
            [genegraph.annotate :as ann]
            [genegraph.database.query :as q]
            [genegraph.interceptor :as intercept :refer [interceptor-enter-def]]
            [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.drug :as drug]
            [genegraph.suggest.serder :as serder]
            [genegraph.suggest.infix-suggester :as suggest]
            [io.pedestal.log :as log]))

(def suggesters (atom {}))

(defn label [resource]
  (first (concat (:skos/preferred-label resource)
                 (:rdfs/label resource))))

(def disease-query (q/create-query (str "select ?s WHERE "
                                        "{ ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                                        "<http://purl.obolibrary.org/obo/MONDO_0000001> ."
                                        "FILTER (!isBlank(?s)) }")))

(defn disease-payload [disease]
  "Create a disease suggester payload"
  (let [iri (str disease)
        label (label disease)
        curie (q/curie disease)
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

(def gene-query (q/create-query "select ?s WHERE { ?s a :so/ProteinCodingGene }"))

(defn gene-payload [gene]
  "Create a gene suggester payload"
  (let [iri (str gene)
        label (label gene)
        curie (q/curie gene)
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
  (let [iri (str drug)
        label  (label drug)
        curie (q/curie drug)
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

(defn suggesters-map []
  "Function returning the suggester configuration."
  ;; fn ensures proper value of env/data-vol which may have been redef'ed
  {:disease {:dirpath (str "file://" env/data-vol "/suggestions/diseases")
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
                              (let [map (get (suggesters-map) key)
                                    suggester (suggest/create-suggester (:dirpath map))]
                                (assoc coll key suggester)))
                            {}
                            (keys (suggesters-map)))]
  (reset! suggesters sugg-map)))

(defn close-suggesters []
  "Close all configured suggesters"
  (doseq [suggester (vals @suggesters)]
    (suggest/close-suggester suggester)))

(defn build-suggestions [key]
  "Run a database query from which to build a suggester index, and persist it"
  (log/info :fn :build-suggestions :suggester key :msg :start)
  (let [map (get (suggesters-map) key)
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

(defn get-suggester-result-map [resource-iri suggester-key]
  (let [resource (q/resource resource-iri)
        label (label resource)]
    (if-let [lookup-result (first (lookup suggester-key label #{:ALL} 1))]
      (let [payload (-> (.payload lookup-result) .bytes serder/deserialize)]
        (if (= resource-iri (:iri payload))
          {:lookup-result lookup-result :resource resource :payload payload}
          nil))
      nil)))

(defn process-event-resource [resource-iri suggester-type]
  (log/info :fn :process-event-resource :resource-type suggester-type :resource-iri resource-iri)
  (when-let [resource-payload-map (get-suggester-result-map resource-iri suggester-type)]
    (let [suggester (get-suggester suggester-type)
          suggest-map (get (suggesters-map) suggester-type)
          resource (:resource resource-payload-map)
          old-payload (:payload resource-payload-map)
          new-payload ((suggest-map :payload) resource)]
      (if-not (= (:curations old-payload) (:curations new-payload))
        (do
          (suggest/update-suggestion suggester
                                     (:label new-payload)
                                     new-payload
                                     (:curations new-payload)
                                     (:weight new-payload))
          (suggest/commit-suggester suggester)
          (suggest/refresh-suggester suggester)
          (log/info :fn :process-event-resource :suggester suggester-type :text (:label new-payload) :msg :updated))
        (log/info :fn :process-event-resource :suggester suggester-type :text (:label new-payload) :msg :not-updated)))))

(defn update-suggesters [event]
  (log/debug :fn :update-suggesters :event event :msg :received-event)
  (when-let [subjects (::ann/subjects event)]
    (when-let [gene-iris (:gene-iris subjects)]
      (map #(process-event-resource % :gene) gene-iris))
    (when-let [disease-iris (:disease-iris subjects)]
      (map #(process-event-resource % :disease) disease-iris)))
  event)

(def update-suggesters-interceptor
  "Interceptor for updating gene and disease suggesters with curation activities"
  (interceptor-enter-def ::update-suggesters update-suggesters))
         
(defstate suggestions
  :start (create-suggesters)
  :stop (close-suggesters))
