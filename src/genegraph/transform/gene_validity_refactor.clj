(ns genegraph.transform.gene-validity-refactor
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q :refer [select construct ld-> ld1-> declare-query]]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]
            [cheshire.core :as json]
            [clojure.walk :refer [postwalk]]
            [clojure.string :as s]
            [clojure.java.io :as io :refer [resource]])
  (:import java.io.ByteArrayInputStream ))

(def base "http://dataexchange.clinicalgenome.org/gci/")

(def legacy-report-base "http://dataexchange.clinicalgenome.org/gci/legacy-report_")

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
                  "pmid" "https://pubmed.ncbi.nlm.nih.gov/"
                  "geno" "http://purl.obolibrary.org/obo/GENO_"})

(declare-query construct-proposition
               construct-evidence-level-assertion
               construct-experimental-evidence-assertions
               construct-genetic-evidence-assertion
               construct-ad-variant-assertions
               construct-ar-variant-assertions
               construct-cc-and-seg-assertions
               construct-proband-score
               construct-model-systems-evidence
               construct-functional-alteration-evidence
               construct-functional-evidence
               construct-rescue-evidence
               construct-case-control-evidence
               construct-segregation-evidence
               construct-evidence-connections
               construct-alleles
               construct-articles
               construct-secondary-contributions
               construct-variant-score
               construct-unscoreable-evidence
               )

;; Trim trailing }, intended to be appended to gci json
(def context
  (s/join ""
        (drop-last
         (json/generate-string
          {"@context" 
           {
            ;; frontmatter
            "@vocab" "http://dataexchange.clinicalgenome.org/gci/"
            "@base" "http://dataexchange.clinicalgenome.org/gci/"

            "PK" "@id"
            "item_type" "@type"
            "uuid" "@id"

            "gci" "http://dataexchange.clinicalgenome.org/gci/"
            "gcixform" "http://dataexchange.clinicalgenome.org/gcixform/"

            ;; ;; common prefixes
            "HGNC" "https://identifiers.org/hgnc:"
            "MONDO" "http://purl.obolibrary.org/obo/MONDO_"
            "SEPIO" "http://purl.obolibrary.org/obo/SEPIO_"
            "GENO" "http://purl.obolibrary.org/obo/GENO_"
            "NCIT" "http://purl.obolibrary.org/obo/NCIT_"
            
            ;; ;; declare attributes with @id, @vocab types
            "hgncId" {"@type" "@id"}

            "autoClassification" {"@type" "@vocab"}
            "alteredClassification" {"@type" "@vocab"}
            "diseaseId" {"@type" "@id"}
            "caseInfoType" {"@type" "@id"}
            "variantType" {"@type" "@id"}
            "caseControl" {"@type" "@id"}                           
            ;; "experimental_scored" {"@type" "@id"}
            ;; "caseControl_scored" {"@type" "@id"}
            ;; "variants" {"@type" "@id"}

            "modelSystemsType" {"@type" "@vocab"}
            "evidenceType" {"@type" "@vocab"}
            "functionalAlterationType" {"@type" "@vocab"}
            "rescueType" {"@type" "@vocab"}
            "studyType" {"@type" "@vocab"}
            "sequencingMethod" {"@type" "@vocab"}
            "authors" {"@container" "@list"}
            "recessiveZygosity" {"@type" "@vocab"}
            "sopVersion" {"@type" "@vocab"}
            "sex" {"@type" "@vocab"}
            "ethnicity" {"@type" "@vocab"}
            "ageType" {"@type" "@vocab"}
            "ageUnit" {"@type" "@vocab"}
            "scoreStatus" {"@type" "@vocab"}
            ;; "testingMethods" {"@type" "@vocab"}

            ;; ;; Category names
            "Model Systems" "gcixform:ModelSystems"
            "Functional Alteration" "gcixform:FunctionalAlteration"
            "Case control" "gcixform:CaseControl"

            ;; Case control
            "Aggregate variant analysis" "gcixform:AggregateVariantAnalysis"
            "Single variant analysis" "gcixform:SingleVariantAnalysis"

            ;; segregation
            ;; "Candidate gene sequencing" "gcixform:CandidateGeneSequencing"
            ;; "Exome/genome or all genes sequenced in linkage region" "gcixform:ExomeSequencing"

            "Candidate gene sequencing" "SEPIO:0004543"
            "Exome/genome or all genes sequenced in linkage region" "SEPIO:0004541"

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

            ;; ;; evidence strength
            "No Modification" "gcixform:NoModification"
            "Definitive" "SEPIO:0004504"
            "Strong" "SEPIO:0004505"
            "Moderate" "SEPIO:0004506"
            "Limited" "SEPIO:0004507"
            "No Known Disease Relationship" "SEPIO:0004508"
            "No Reported Evidence" "SEPIO:0004508" ;; investigate the use of this
            "Refuted" "SEPIO:0004510"
            "Disputed" "SEPIO:0004540"
            "No Classification" "SEPIO:0004508" ;; Maybe this should not exist in published records?
            ;; "No Classification" "SEPIO:0004508"

            ;; Zygosity
            "Homozygous" "GENO:0000136"
            "TwoTrans" "GENO:0000135"
            "Hemizygous" "GENO:0000134"

            ;; SOP versions
            "4" "SEPIO:0004092"
            "5" "SEPIO:0004093"
            "6" "SEPIO:0004094"
            "7" "SEPIO:0004095"
            "8" "SEPIO:0004096"

            ;; Sex
            "Ambiguous" "SEPIO:0004574"
            "Female" "SEPIO:0004575"
            "Intersex" "SEPIO:0004576"
            "Male" "SEPIO:0004578"
            ;; "Unknown" "SEPIO:0004570"

            ;; ethnicity
            "Hispanic or Latino" "SEPIO:0004568"
            "Not Hispanic or Latino" "SEPIO:0004569"
            "Unknown" "SEPIO:0004570"

            ;; ageType
            "Death" "SEPIO:0004562"
            "Diagnosis" "SEPIO:0004563"
            "Onset" "SEPIO:0004564"
            "Report" "SEPIO:0004565"
 
            ;; ageUnit
            "Days" "SEPIO:0004552"
            "Hours" "SEPIO:0004553"
            "Months" "SEPIO:0004554"
            "Weeks" "SEPIO:0004555"
            "Weeks gestation" "SEPIO:0004556" 
            "Years" "SEPIO:0004557"

            ;; scoreStatus
            "Contradicts" "SEPIO:0004581"
            "Review" "SEPIO:0004582"
            "Score" "SEPIO:0004583"
            "Supports" "SEPIO:0004584"
            "none" "SEPIO:0004585"

            ;; testingMethods
            "Chromosomal microarray" "SEPIO:0004591"
            "Denaturing gradient gel" "SEPIO:0004592"
            "Exome sequencing" "SEPIO:0004593"
            "Genotyping" "SEPIO:0004594"
            "High resolution melting" "SEPIO:0004595"
            "Homozygosity mapping" "SEPIO:0004596"
            "Linkage analysis" "SEPIO:0004597"
            "Next generation sequencing panels" "SEPIO:0004598"
            "Other" "SEPIO:0004599"
            "PCR" "SEPIO:0004600"
            "Restriction digest" "SEPIO:0004601"
            "SSCP" "SEPIO:0004602"
            "Sanger sequencing" "SEPIO:0004603"
            "Whole genome shotgun sequencing" "SEPIO:0004604"

            ;; variantType
            "OTHER_VARIANT_TYPE" "SEPIO:0004611"
            "PREDICTED_OR_PROVEN_NULL" "SEPIO:0004612"

            }}))))

(defn clear-associated-snapshots [gdm-json]
  (->> (json/parse-string gdm-json)
       (postwalk #(if (map? %) (dissoc % "associatedClassificationSnapshots") %))
       json/generate-string))

(defn fix-gdm-identifiers [gdm-json]
  (-> gdm-json
      (s/replace #"MONDO_" "MONDO:")
      (s/replace #"@id" "gciid")))

(defn append-context [gdm-json]
  (str context "," (subs gdm-json 1)))

(defn str->bytestream [s]
  (-> s .getBytes ByteArrayInputStream.))

(defn parse-gdm [gdm-json]
  (-> gdm-json
      clear-associated-snapshots
      fix-gdm-identifiers
      append-context
      str->bytestream
      (l/read-rdf {:format :json-ld})))

(def gdm-is-about-gene-query
  (q/create-query "prefix gci: <http://dataexchange.clinicalgenome.org/gci/>
  select ?hgnc where { 
 ?gdm a gci:gdm .
 ?gdm gci:gene ?gene .
 ?gene gci:hgncId ?hgnc }"))

(def hgnc-has-equiv-entrez-gene-query
  (q/create-query "select ?gene where { ?gene :owl/same-as ?hgnc_gene }"))

(defn transform-gdm [gdm]
  (.setNsPrefixes gdm ns-prefixes)
  (let [gdm-is-about-gene (first (gdm-is-about-gene-query {::q/model gdm}))
        entrez-gene (first (hgnc-has-equiv-entrez-gene-query {:hgnc_gene gdm-is-about-gene}))
        params {::q/model (q/union gdm gdm-sepio-relationships)
                :gcibase base
                :legacy_report_base legacy-report-base
                :arbase "http://reg.genome.network/allele/"
                :cvbase "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
                :pmbase "https://pubmed.ncbi.nlm.nih.gov/"
                :affbase "http://dataexchange.clinicalgenome.org/agent/"
                :entrez_gene entrez-gene}
        unlinked-model (q/union 
                        (construct-proposition params)
                        (construct-evidence-level-assertion params)
                        (construct-experimental-evidence-assertions params)
                        (construct-genetic-evidence-assertion params)
                        (construct-ad-variant-assertions params)
                        (construct-ar-variant-assertions params)
                        (construct-cc-and-seg-assertions params)
                        (construct-proband-score params)
                        (construct-model-systems-evidence params)
                        (construct-functional-evidence params)
                        (construct-functional-alteration-evidence params)
                        (construct-rescue-evidence params)
                        (construct-case-control-evidence params)
                        (construct-segregation-evidence params)
                        (construct-alleles params)
                        (construct-articles params)
                        (construct-secondary-contributions params)
                        (construct-variant-score params)
                        (construct-unscoreable-evidence params)
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
