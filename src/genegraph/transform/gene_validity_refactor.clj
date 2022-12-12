(ns genegraph.transform.gene-validity-refactor
  (:require [genegraph.database.load :as l]
            [genegraph.util :refer [str->bytestream]]
            [genegraph.database.query :as q :refer [select construct ld-> ld1-> declare-query]]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]
            [cheshire.core :as json]
            [clojure.walk :refer [postwalk]]
            [clojure.string :as s]
            [clojure.java.io :as io :refer [resource]]))

(def base "http://dataexchange.clinicalgenome.org/gci/")
(def legacy-report-base "http://dataexchange.clinicalgenome.org/gci/legacy-report_")
(def affbase "http://dataexchange.clinicalgenome.org/agent/")

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
               construct-proband-segregation-evidence
               construct-family-segregation-evidence
               construct-evidence-connections
               construct-alleles
               construct-articles
               construct-earliest-articles
               construct-secondary-contributions
               construct-variant-score
               construct-ar-variant-score
               construct-unscoreable-evidence
               unlink-variant-scores-when-proband-scores-exist
               unlink-segregations-when-no-proband-and-lod-scores
               add-legacy-website-id)


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
            "HP" {"@id" "http://purl.obolibrary.org/obo/HP_"
                  "@prefix" true}
            ;; ;; declare attributes with @id, @vocab types
            "hgncId" {"@type" "@id"}

            "autoClassification" {"@type" "@vocab"}
            "alteredClassification" {"@type" "@vocab"}
            "hpoIdInDiagnosis" {"@type" "@id"}
            "diseaseId" {"@type" "@id"}
            "caseInfoType" {"@type" "@id"}
            "variantType" {"@type" "@id"}
            "caseControl" {"@type" "@id"}
            "affiliation" {"@type" "@id"}
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
            "interactionType" {"@type" "@vocab"}
            "probandIs" {"@type" "@vocab"}
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

            "Candidate gene sequencing" "http://purl.obolibrary.org/obo/SEPIO_0004543"
            "Exome genome or all genes sequenced in linkage region" "http://purl.obolibrary.org/obo/SEPIO_0004541"

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
            "Definitive" "http://purl.obolibrary.org/obo/SEPIO_0004504"
            "Strong" "http://purl.obolibrary.org/obo/SEPIO_0004505"
            "Moderate" "http://purl.obolibrary.org/obo/SEPIO_0004506"
            "Limited" "http://purl.obolibrary.org/obo/SEPIO_0004507"
            "No Known Disease Relationship" "http://purl.obolibrary.org/obo/SEPIO_0004508"
            "No Reported Evidence" "http://purl.obolibrary.org/obo/SEPIO_0004508" ;; investigate the use of this
            "Refuted" "http://purl.obolibrary.org/obo/SEPIO_0004510"
            "Disputed" "http://purl.obolibrary.org/obo/SEPIO_0004540"
            "No Classification" "http://purl.obolibrary.org/obo/SEPIO_0004508" ;; Maybe this should not exist in published records?
            ;; "No Classification" "http://purl.obolibrary.org/obo/SEPIO_0004508"

            ;; Zygosity
            "Homozygous" "http://purl.obolibrary.org/obo/GENO_0000136"
            "TwoTrans" "http://purl.obolibrary.org/obo/GENO_0000135"
            "Hemizygous" "http://purl.obolibrary.org/obo/GENO_0000134"

            ;; SOP versions
            "4" "http://purl.obolibrary.org/obo/SEPIO_0004092"
            "5" "http://purl.obolibrary.org/obo/SEPIO_0004093"
            "6" "http://purl.obolibrary.org/obo/SEPIO_0004094"
            "7" "http://purl.obolibrary.org/obo/SEPIO_0004095"
            "8" "http://purl.obolibrary.org/obo/SEPIO_0004096"
            "9" "http://purl.obolibrary.org/obo/SEPIO_0004171"


            ;; Sex
            "Ambiguous" "http://purl.obolibrary.org/obo/SEPIO_0004574"
            "Female" "http://purl.obolibrary.org/obo/SEPIO_0004575"
            "Intersex" "http://purl.obolibrary.org/obo/SEPIO_0004576"
            "Male" "http://purl.obolibrary.org/obo/SEPIO_0004578"
            ;; "Unknown" "http://purl.obolibrary.org/obo/SEPIO_0004570"

            ;; ethnicity
            "Hispanic or Latino" "http://purl.obolibrary.org/obo/SEPIO_0004568"
            "Not Hispanic or Latino" "http://purl.obolibrary.org/obo/SEPIO_0004569"
            "Unknown" "http://purl.obolibrary.org/obo/SEPIO_0004570"

            ;; ageType
            "Death" "http://purl.obolibrary.org/obo/SEPIO_0004562"
            "Diagnosis" "http://purl.obolibrary.org/obo/SEPIO_0004563"
            "Onset" "http://purl.obolibrary.org/obo/SEPIO_0004564"
            "Report" "http://purl.obolibrary.org/obo/SEPIO_0004565"
 
            ;; ageUnit
            "Days" "http://purl.obolibrary.org/obo/SEPIO_0004552"
            "Hours" "http://purl.obolibrary.org/obo/SEPIO_0004553"
            "Months" "http://purl.obolibrary.org/obo/SEPIO_0004554"
            "Weeks" "http://purl.obolibrary.org/obo/SEPIO_0004555"
            "Weeks gestation" "http://purl.obolibrary.org/obo/SEPIO_0004556" 
            "Years" "http://purl.obolibrary.org/obo/SEPIO_0004557"

            ;; scoreStatus
            "Contradicts" "http://purl.obolibrary.org/obo/SEPIO_0004581"
            "Review" "http://purl.obolibrary.org/obo/SEPIO_0004582"
            "Score" "http://purl.obolibrary.org/obo/SEPIO_0004583"
            "Supports" "http://purl.obolibrary.org/obo/SEPIO_0004584"
            "none" "http://purl.obolibrary.org/obo/SEPIO_0004585"

            ;; testingMethods
            "Chromosomal microarray" "http://purl.obolibrary.org/obo/SEPIO_0004591"
            "Denaturing gradient gel" "http://purl.obolibrary.org/obo/SEPIO_0004592"
            "Exome sequencing" "http://purl.obolibrary.org/obo/SEPIO_0004593"
            "Genotyping" "http://purl.obolibrary.org/obo/SEPIO_0004594"
            "High resolution melting" "http://purl.obolibrary.org/obo/SEPIO_0004595"
            "Homozygosity mapping" "http://purl.obolibrary.org/obo/SEPIO_0004596"
            "Linkage analysis" "http://purl.obolibrary.org/obo/SEPIO_0004597"
            "Next generation sequencing panels" "http://purl.obolibrary.org/obo/SEPIO_0004598"
            "Other" "http://purl.obolibrary.org/obo/SEPIO_0004599"
            "PCR" "http://purl.obolibrary.org/obo/SEPIO_0004600"
            "Restriction digest" "http://purl.obolibrary.org/obo/SEPIO_0004601"
            "SSCP" "http://purl.obolibrary.org/obo/SEPIO_0004602"
            "Sanger sequencing" "http://purl.obolibrary.org/obo/SEPIO_0004603"
            "Whole genome shotgun sequencing" "http://purl.obolibrary.org/obo/SEPIO_0004604"

            ;; variantType
            "OTHER_VARIANT_TYPE" "http://purl.obolibrary.org/obo/SEPIO_0004611"
            "PREDICTED_OR_PROVEN_NULL" "http://purl.obolibrary.org/obo/SEPIO_0004612"

            ;; interactionTypes
            "genetic interaction" "gcixform:GeneticInteraction"
            "negative genetic interaction" "gcixform:NegativeGeneticInteraction"
            "physical association" "gcixform:PhysicalAssociation"
            "positive genetic interaction" "gcixform:PositiveGeneticInteraction"

            ;; probandIs
            "Biallelic compound heterozygous" "http://purl.obolibrary.org/obo/GENO_0000402"
            "Biallelic homozygous" "http://purl.obolibrary.org/obo/GENO_0000136"
            "Monoallelic heterozygous"  "http://purl.obolibrary.org/obo/GENO_0000135"
            
            }}))))

(defn expand-affiliation-to-iri
  "Expand affiliation when a simple string field, to be an iri"
  [m]
  (if (and (map? m) (get m "affiliation"))
    (update m "affiliation" (fn [affiliation]
                              (if (coll? affiliation)
                                affiliation
                                (str affbase affiliation))))
          m))

(defn fix-hpo-ids [m]
  (if (and (map? m) (get m "hpoIdInDiagnosis"))
    (update m "hpoIdInDiagnosis" (fn [phenotypes]
                                   (mapv #(re-find #"HP:\d{7}" %)
                                         phenotypes)))
    m))

(comment
  (fix-hpo-ids {"hpoIdInDiagnosis"
                ["Infantile muscular hypotonia (HP:0008947)"
                 "Proximal muscle weakness in upper limbs (HP:0008997)"
                 "Foot dorsiflexor weakness (HP:0009027)"
                 "Muscular hypotonia of the trunk (HP:0008936)"
                 "Delayed gross motor development (HP:0002194)"
                 "Distal amyotrophy (HP:0003693)"
                 "Decreased sensory nerve conduction velocity (HP:0003448)"
                 "Peripheral demyelination (HP:0011096)"
                 "Onion bulb formation (HP:0003383)"
                 "Decreased compound muscle action potential amplitude (HP:0033383)"
                 "Distal muscle weakness (HP:0002460)"
                 "Abnormal macrophage count (HP:0030326)"]})

  (fix-hpo-ids {"hpoidindiagnosis"
                ["HP:0001252"
                 "HP:0002118"
                 "HP:0004325"
                 "HP:0009128"
                 "HP:0002107"]}))

(defn clear-associated-snapshots [m]
  (if (map? m) (dissoc m "associatedClassificationSnapshots") m))


(defn remove-key-when-empty
  [m key]
  (postwalk (fn [x] (if (and (map? x)
                             (some-> (get x key)
                                     empty?))
                      (dissoc x key)
                      x))
            m))


(defn preprocess-json
  "Walk GCI JSON prior to parsing as JSON-LD to clean up data."
  [gci-json]
  (->> (json/parse-string gci-json)
       (postwalk #(-> %
                      clear-associated-snapshots
                      fix-hpo-ids
                      expand-affiliation-to-iri
                      (remove-key-when-empty "geneWithSameFunctionSameDisease")
                      (remove-key-when-empty "normalExpression")
                      (remove-key-when-empty "scores")))
       json/generate-string))


(defn fix-gdm-identifiers [gdm-json]
  (-> gdm-json
      (s/replace #"MONDO_" "http://purl.obolibrary.org/obo/MONDO_")
      ;; New json-ld parser doesn't like '/' or parenthesis in terms 
      (s/replace #"Exome/genome or all genes sequenced in linkage region"
                 "Exome genome or all genes sequenced in linkage region")
      ;; these are the interactionType MI codes only -  MI codes are used
      ;; in at least one other field in the json. Removing the MI code
      ;; completely as we are not preserving the actual interactionType
      (s/replace #" \(MI:0208\)| \(MI:0915\)| \(MI:0933\)| \(MI:0935\)" "")
      (s/replace #"@id" "gciid")))

(defn append-context [gdm-json]
  (str context "," (subs gdm-json 1)))

(defn parse-gdm [gdm-json]
  (-> gdm-json
      preprocess-json
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

(defn add-proband-scores
  "Return model contributing the evidence line scores for proband scores
  when needed in SOPv8 + autosomal recessive variants. May need a mechanism
  to feed a new cap in, should that change."
  [model]
  (let [proband-evidence-lines
        (q/select "select ?x where { ?x a :sepio/ProbandScoreCapEvidenceLine }" {} model)]
    (q/union
     model
     (l/statements-to-model
      (map
       #(vector 
         %
         :sepio/evidence-line-strength-score
         (min 3 ; current cap on sop v8+ proband scores
              (reduce
               + 
               (ld-> % [:sepio/has-evidence
                        :sepio/evidence-line-strength-score]))))
       proband-evidence-lines)))))

(defn legacy-website-id
  "The website uses a version of the assertion ID that incorporates
  the approval date. Annotate the curation with this ID to retain
  backward compatibility with the legacy schema."
  [model]
  (let [approval-date (some-> (q/select
                               "select ?activity where { ?activity :bfo/realizes  :sepio/ApproverRole }"
                               {}
                               model)
                              first
                              (q/ld1-> [:sepio/activity-date]))
        
        [_
         assertion-base
         assertion-id]
        (some->> (q/select
                                 "select ?assertion where { ?assertion a :sepio/GeneValidityEvidenceLevelAssertion }"
                           {}
                           model)                                
                                first
                                str
                                (re-find #"^(.*/)([a-z0-9-]*)$"))]
    (q/resource (str assertion-base "assertion_" assertion-id "-" approval-date))))


(def has-affiliation-query
  "Query that returns a curations full affiliation IRI as a Resource.
  Expects affiliations to have been preprocessed to IRIs from string form."
  (q/create-query "prefix gci: <http://dataexchange.clinicalgenome.org/gci/>
                   select ?affiliationIRI where {
                     ?proposition a gci:gdm .
                     OPTIONAL {
                      ?proposition gci:affiliation ?gdmAffiliationIRI .
                     }
                     OPTIONAL {
                      ?classification a gci:provisionalClassification .
                      ?classification gci:affiliation ?classificationAffiliationIRI .
                      ?classification gci:last_modified ?date .
                     }
                     BIND(COALESCE(?classificationAffiliationIRI, ?gdmAffiliationIRI) AS ?affiliationIRI) }
                     ORDER BY DESC(?date) LIMIT 1"))

(defn transform-gdm [gdm]
  (.setNsPrefixes gdm ns-prefixes)
  (let [gdm-is-about-gene (first (gdm-is-about-gene-query {::q/model gdm}))
        entrez-gene (first (hgnc-has-equiv-entrez-gene-query {:hgnc_gene gdm-is-about-gene}))
        affiliation (first (has-affiliation-query {::q/model gdm}))
        params {::q/model (q/union gdm gdm-sepio-relationships)
                :gcibase base
                :legacy_report_base legacy-report-base
                :affiliation affiliation
                :arbase "http://reg.genome.network/allele/"
                :cvbase "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
                :pmbase "https://pubmed.ncbi.nlm.nih.gov/"
                :affbase affbase
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
                        (construct-proband-segregation-evidence params)
                        (construct-family-segregation-evidence params)
                        (construct-alleles params)
                        (construct-articles params)
                        (construct-earliest-articles params)
                        (construct-secondary-contributions params)
                        (construct-variant-score params)
                        (construct-ar-variant-score params)
                        (construct-unscoreable-evidence params)
                        )
        unlinked-modified (unlink-segregations-when-no-proband-and-lod-scores
                             {::q/model unlinked-model})
        linked-model (q/union unlinked-modified
                              (construct-evidence-connections 
                               {::q/model
                                (q/union unlinked-modified
                                         gdm-sepio-relationships)})
                              (add-legacy-website-id
                               {::q/model unlinked-modified
                                :legacy_id (legacy-website-id unlinked-modified)}
                               ))]
    (unlink-variant-scores-when-proband-scores-exist
     {::q/model (add-proband-scores linked-model)})))

(defmethod add-model :gci-refactor
  [event]
  (assoc event
         ::q/model
         (-> event
             :genegraph.sink.event/value
             parse-gdm
             transform-gdm)))
