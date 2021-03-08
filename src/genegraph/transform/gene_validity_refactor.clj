(ns genegraph.transform.gene-validity-refactor
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q :refer [select construct ld-> ld1-> declare-query]]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]
            [cheshire.core :as json]
            [clojure.string :as s]
            [clojure.java.io :as io :refer [resource]])
  (:import java.io.ByteArrayInputStream ))

(def base "http://dataexchange.clinicalgenome.org/gci/")

(def gdm-sepio-relationships (l/read-rdf (str (resource "genegraph/transform/gene_validity_refactor/gdm_sepio_relationships.ttl")) {:format :turtle}))

(def ns-prefixes {"dx" "http://dataexchange.clinicalgenome.org/"
                  "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
                  "hp" "http://purl.obolibrary.org/obo/HP_"
                  "dc" "http://purl.org/dc/terms/"
                  "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
                  "dxgci" "http://dataexchange.clinicalgenome.org/gci/"
                  "ro" "http://purl.obolibrary.org/obo/RO_"
                  "mondo" "http://purl.obolibrary.org/obo/MONDO_"
                  "car" "http://reg.genome.network/allele/"
                  "cv" "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
                  "hgnc" "https://identifiers.org/hgnc:"
                  "pmid" "https://pubmed.ncbi.nlm.nih.gov/"})

(declare-query construct-proposition
               construct-evidence-level-assertion
               construct-experimental-evidence-assertions
               construct-genetic-evidence-assertion
               construct-ad-variant-assertions
               construct-ar-variant-assertions
               construct-cc-and-seg-assertions
               construct-proband-score
               ;; construct-model-systems-evidence
               ;; construct-functional-alteration-evidence
               ;; construct-functional-evidence
               ;; construct-rescue-evidence
               ;; construct-case-control-evidence
               ;; construct-segregation-evidence
               construct-evidence-connections
               )

;; Trim trailing }, intended to be appended to gci json
(def context
  (s/join ""
        (drop-last
         (json/generate-string
          {"@context" 
           {
            ;; frontmatter
            "@version" "1.1"
            "@vocab" "http://gci.clinicalgenome.org/"
            "@base" "http://gci.clinicalgenome.org/"

            "PK" "@id"
            "item_type" "@type"

            
            ;; "gci" "http://gci.clinicalgenome.org/"
            ;; "gcixform" "http://dataexchange.clinicalgenome.org/gcixform/"

            ;; ;; common prefixes
            "HGNC" "https://identifiers.org/hgnc:"
            "MONDO" "http://purl.obolibrary.org/obo/MONDO_"
            "SEPIO" "http://purl.obolibrary.org/obo/SEPIO_"
            
            ;; ;; declare attributes with @id, @vocab types
            "hgncId" {"@type" "@id"}
            "autoClassification" {"@type" "@vocab"}
            ;; "diseaseId" {"@type" "@id"}
            "caseInfoType" {"@type" "@id"}
            ;; "experimental_scored" {"@type" "@id"}
            ;; "caseControl_scored" {"@type" "@id"}
            ;; "variants" {"@type" "@id"}

            ;; "modelSystemsType" {"@type" "@vocab"}
            ;; "evidenceType" {"@type" "@vocab"}
            ;; "functionalAlterationType" {"@type" "@vocab"}
            ;; "rescueType" {"@type" "@vocab"}
            ;; "studyType" {"@type" "@vocab"}
            ;; "sequencingMethod" {"@type" "@vocab"}


            ;; ;; Category names
            ;; "Model Systems" "gcixform:ModelSystems"
            ;; "Functional Alteration" "gcixform:FunctionalAlteration"
            ;; "Case control" "gcixform:CaseControl"

            ;; ;; Case control
            ;; "Aggregate variant analysis" "gcixform:AggregateVariantAnalysis"
            ;; "Single variant analysis" "gcixform:SingleVariantAnalysis"

            ;; ;; segregation
            ;; "Candidate gene sequencing" "gcixform:CandidateGeneSequencing"
            ;; "Exome/genome or all genes sequenced in linkage region" "gcixform:ExomeSequencing"

            ;; ;; Experimental evidence types
            ;; "Expression" "gcixform:Expression"
            ;; "Biochemical Function" "gcixform:BiochemicalFunction"
            ;; "Protein Interactions" "gcixform:ProteinInteraction"

            ;; ;; rescue
            ;; "Cell culture" "gcixform:CellCulture"
            ;; "Non-human model organism" "gcixform:NonHumanModel"
            ;; "Patient cells" "gcixform:PatientCells"
            ;; "Human" "gcixform:Human"

            ;; ;; model systems
            ;; "Cell culture model" "gcixform:CellCultureModel"

            ;; ;; functional alteration
            ;; "Non-patient cells" "gcixform:NonPatientCells"
            ;; "patient cells" "gcixform:PatientCells"
;; #{"Definitive" "Limited" "No Known Disease Relationship" "Moderate"
;;   "No Classification" "Strong"}
            ;; ;; evidence strength
            "Definitive" "SEPIO:0004504"
            "Strong" "SEPIO:0004505"
            "Moderate" "SEPIO:0004506"
            "Limited" "sepio:0004507"
            "No Known Disease Relationship" "SEPIO:0004508"
            "No Classification" "SEPIO:0004508" ;; Maybe this should not exist in published records?
            ;; "No Classification" "SEPIO:0004508"
            }}))))

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
                        ;; (construct-proposition params)
                        ;; (construct-evidence-level-assertion params)
                        ;; (construct-experimental-evidence-assertions params)
                        ;; (construct-genetic-evidence-assertion params)
                        ;; (construct-ad-variant-assertions params)
                        ;; (construct-ar-variant-assertions params)
                        ;; (construct-cc-and-seg-assertions params)
                        (construct-proband-score params)
                        
                        ;; (construct-model-systems-evidence params)
                        ;; (construct-functional-alteration-evidence params)
                        ;; (construct-functional-evidence params)
                        ;; (construct-rescue-evidence params)
                        ;; (construct-case-control-evidence params)
                        ;; (construct-segregation-evidence params)
                        )]
    (q/union unlinked-model
             (construct-evidence-connections 
              {::q/model
               (q/union unlinked-model
                        gdm-sepio-relationships)}))))

(defmethod add-model :gci-refactor
  [event]
  (assoc event
         ::q/model
         (-> event
             :genegraph.sink.event/value
             parse-gdm
             transform-gdm)))
