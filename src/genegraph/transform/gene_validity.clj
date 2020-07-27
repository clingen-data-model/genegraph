(ns genegraph.transform.gene-validity
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q :refer [select construct ld-> ld1-> declare-query]]
            [genegraph.transform.core :refer [transform-doc src-path add-model]]
            [cheshire.core :as json]
            [clojure.string :as s]
            [clojure.java.io :as io :refer [resource]])
  (:import java.io.ByteArrayInputStream ))

(def base "http://dataexchange.clinicalgenome.org/gci/")

(def gdm-sepio-relationships (l/read-rdf (str (resource "genegraph/transform/gene_validity/gdm_sepio_relationships.ttl")) {:format :turtle}))

(def ns-prefixes {"dx" "http://dataexchange.clinicalgenome.org/"
                  "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
                  "dc" "http://purl.org/dc/terms/"
                  "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
                  "dxgci" "http://dataexchange.clinicalgenome.org/gci/"
                  "ro" "http://purl.obolibrary.org/obo/RO_"
                  "mondo" "http://purl.obolibrary.org/obo/MONDO_"
                  "car" "http://reg.genome.network/allele/"
                  "cv" "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
                  "pmid" "https://pubmed.ncbi.nlm.nih.gov/"})

(declare-query construct-proposition
               construct-evidence-level-assertion
               construct-proband-score
               construct-model-systems-evidence
               construct-functional-alteration-evidence
               construct-functional-evidence
               construct-rescue-evidence
               construct-case-control-evidence
               construct-segregation-evidence
               construct-evidence-connections)

;; Trim trailing }, intended to be appended to gci json
(def context
  (s/join ""
        (drop-last
         (json/generate-string
          {"@context" 
           {
            ;; frontmatter
            "@vocab" "http://gci.clinicalgenome.org/"
            "@base" "http://gci.clinicalgenome.org/"
            "gci" "http://gci.clinicalgenome.org/"
            "gcixform" "http://dataexchange.clinicalgenome.org/gcixform/"

            ;; common prefixes
            "MONDO" "http://purl.obolibrary.org/obo/MONDO_"
            "SEPIO" "http://purl.obolibrary.org/obo/SEPIO_"
            
            ;; declare attributes with @id, @vocab types
            "hgncId" {"@type" "@id"}
            "diseaseId" {"@type" "@id"}
            "caseInfoType" {"@type" "@id"}
            "experimental_scored" {"@type" "@id"}
            "caseControl_scored" {"@type" "@id"}
            "variants" {"@type" "@id"}
            "autoClassification" {"@type" "@vocab"}
            "modelSystemsType" {"@type" "@vocab"}
            "evidenceType" {"@type" "@vocab"}
            "functionalAlterationType" {"@type" "@vocab"}
            "rescueType" {"@type" "@vocab"}
            "studyType" {"@type" "@vocab"}
            "sequencingMethod" {"@type" "@vocab"}


            ;; Category names
            "Model Systems" "gcixform:ModelSystems"
            "Functional Alteration" "gcixform:FunctionalAlteration"
            "Case control" "gcixform:CaseControl"

            ;; Case control
            "Aggregate variant analysis" "gcixform:AggregateVariantAnalysis"
            "Single variant analysis" "gcixform:SingleVariantAnalysis"

            ;; segregation
            "Candidate gene sequencing" "gcixform:CandidateGeneSequencing"
            "Exome/genome or all genes sequenced in linkage region" "gcixform:ExomeSequencing"

            ;; Experimental evidence types
            "Expression" "gcixform:Expression"
            "Biochemical Function" "gcixform:BiochemicalFunction"
            "Protein Interactions" "gcixform:ProteinInteraction"

            ;; rescue
            "Cell culture" "gcixform:CellCulture"
            "Non-human model organism" "gcixform:NonHumanModel"
            "Patient cells" "gcixform:PatientCells"
            "Human" "gcixform:Human"

            ;; model systems
            "Cell culture model" "gcixform:CellCultureModel"

            ;; functional alteration
            "Non-patient cells" "gcixform:NonPatientCells"
            "patient cells" "gcixform:PatientCells"

            ;; evidence strength
            "Moderate" "SEPIO:0004506"
            "Definitive" "SEPIO:0004504"
            "No Classification" "SEPIO:0004508"}}))))

(defn parse-gdm [gdm-json]
  (let [gdm-with-fixed-curies (s/replace gdm-json #"MONDO_" "MONDO:")
        is (-> (str context "," (subs gdm-with-fixed-curies 1))
               .getBytes
               ByteArrayInputStream.)]
    (l/read-rdf is {:format :json-ld})))


(defn transform-gdm [gdm]
  (.setNsPrefixes gdm ns-prefixes)
  (let [params {::q/model (q/union gdm gdm-sepio-relationships)
                :gcibase base
                :arbase "http://reg.genome.network/allele/"
                :cvbase "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
                :pmbase "https://pubmed.ncbi.nlm.nih.gov/"}
        unlinked-model (q/union 
                        (construct-proposition params)
                        (construct-evidence-level-assertion params)
                        (construct-model-systems-evidence params)
                        (construct-functional-alteration-evidence params)
                        (construct-functional-evidence params)
                        (construct-proband-score params)
                        (construct-rescue-evidence params)
                        (construct-case-control-evidence params)
                        (construct-segregation-evidence params))]
    (q/union unlinked-model
             (construct-evidence-connections 
              {::q/model
               (q/union unlinked-model
                        gdm-sepio-relationships)}))))


(defn partition-snapshot 
  "Utility function to split GCM snapshot into separate files"
  [src dest]
  (with-open [rdr (io/reader src)]
    (doseq [snapshot (json/parse-stream rdr)]
      (with-open [wtr (io/writer (str dest (get snapshot "uuid") ".json"))]
        (json/generate-stream snapshot wtr {:pretty true})))))

(defn chunk-snapshot-to-events
  "Utility function to split GCM snapshot into streamable events"
  [src dest]
  (with-open [rdr (io/reader src)]
    (doseq [snapshot (json/parse-stream rdr)]
      (with-open [wtr (io/writer (str dest (get snapshot "uuid") ".edn"))]
        (binding [*out* wtr
                  *print-length* nil]
          (pr
           {:genegraph.sink.event/format :gci-snapshot
            :genegraph.sink.event/key (get snapshot "uuid")
            :genegraph.sink.event/value (json/generate-string snapshot)}))))))


(defmethod add-model :gci-snapshot
  [event]
  )
