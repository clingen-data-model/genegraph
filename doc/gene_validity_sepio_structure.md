**construct-proposition**

- goes after type “gdm”
- establishes :sepio/GeneValidityProposition (SEPIO\_0004001)
- has-subject entrez-gene
- has-predicate :ro/IsCausalGermlineMutationIn
- has-object disease
- has-qualifier mode of inheritence

**construct-evidence-level-assertion**

- goes after type “gdm”
- establishes :sepio/GeneValidityEvidenceLevelAssertion (SEPIO\_0004002)
- subject is the proposition id = PK of affiliation = UUID
- subject points to :sepio/GeneValidityProposition (SEPIO\_0004001)
- **THE SUBJECT OF THE ASSERTION IS THE PROPOSITION**
- legacy report id = PK\_legacy\_report 
- adds entry: PK\_legacy\_report bfo:has-part PK
- selects approved and published classifications
- predicate :sepio/HasEvidenceLevel (SEPIO\_0000146)
- object : is evidence level enum
  - :sepio/DefinitiveEvidence "http://purl.obolibrary.org/obo/SEPIO\_0004504"
  - ` `sepio/LimitedEvidence "http://purl.obolibrary.org/obo/SEPIO\_0004507"
  - ` `sepio/ModerateEvidence "http://purl.obolibrary.org/obo/SEPIO\_0004506"
  - ` `sepio/NoEvidence "http://purl.obolibrary.org/obo/SEPIO\_0004508"
  - ` `sepio/RefutingEvidence "http://purl.obolibrary.org/obo/SEPIO\_0004510"
  - ` `sepio/DisputingEvidence "http://purl.obolibrary.org/obo/SEPIO\_0000404"
  - ` `sepio/StrongEvidence "http://purl.obolibrary.org/obo/SEPIO\_0004505"
- has-evidence PK\_auto\_classification
  - autoClassification a :sepio/GeneValidityEvidenceLevelAutoClassification (SEPIO\_0004098)
  - subject is the proposition

**construct-experimental-evidence-assertions**

- goes after type gci:provisionalClassification
  - approvedClassification true
  - with a pointsTree
  - gets PK
- creates each of the following with :sepio/evidence-line-strength-score with appropriate score:
  - PK\_experimental\_evidence\_line a :sepio/OverallExperimentalEvidenceLine (SEPIO\_0004006)
  - PK\_functional\_evidence\_line a :sepio/OverallFunctionalEvidenceLine (SEPIO\_0004013)
  - PK\_functional\_alteration\_evidence\_line a :sepio/OverallFunctionalAlterationEvidenceLine (SEPIO\_0004014)
  - PK\_model\_rescue\_evidence\_line a :sepio/OverallModelAndRescueEvidenceLine (SEPIO\_0004015)

**construct-genetic-evidence-assertion**

- goes after type gci:provisionalClassification
  - approvedClassification true
  - with a pointsTree
  - gets PK
  - gets gci:geneticEvidenceTotal from pointsTree
- adds 
  - PK :sepio/has-evidence PK\_overall\_genetic\_evidence\_line
    - a :sepio/OverallGeneticEvidenceLine (SEPIO\_0004005)
    - evidence-line-strength-score

    - ` `**construct-ad-variant-assertions**
- goes after type gci:provisionalClassification
  - approvedClassification true
  - with a pointsTree
  - gets PK
- adds evidence-line-strength-score scores to
  - PK\_overall\_genetic\_evidence\_line has-evidence 
    - PK\_ad\_other\_el a :sepioOverallAutosomalDominantOtherVariantEvidenceLine (SEPIO\_0004011)
    - PK\_ad\_null\_el a a :sepio/OverallAutosomalDominantNullVariantEvidenceLine (SEPIO\_0004010)
    - PK\_ad\_dn\_el a :sepio/OverallAutosomalDominantDeNovoVariantEvidenceLine (SEPIO\_0004009)

- **construct-ar-variant-assertions**
- goes after type gci:provisionalClassification
  - approvedClassification true
  - with a pointsTree
  - gets PK
- adds evidence-line-strength-score scores to
  - PK\_overall\_genetic\_evidence\_line has-evidence 
    - PK\_ar\_el a :sepio/OverallAutosomalRecessiveVariantEvidenceLine (SEPIO\_0004008)

    - ` `**construct-cc-and-seg-variant-assertions**
- goes after type gci:provisionalClassification
  - approvedClassification true
  - with a pointsTree
  - gets PK
- adds evidence-line-strength-score scores to
  - PK\_overall\_genetic\_evidence\_line has-evidence 
    - PK\_cc\_el a :sepio/OverallCaseControlEvidenceLine (SEPIO\_0004007)
    - PK\_seg\_el a a :sepio/SegregationEvidenceLine(SEPIO\_0004012)

**construct-proband-score**

- Filters out any “?scores a gci:variantScore” to eliminate some sopVersion 7 and all sopVersion 8 curations
- goes after type gci:evidenceScore
  - PK
  - with caseInfoType
  - scoreStatus
  - date\_created
  - calculatedScore
- adds
  - uses the caseInfoType to match to proband type in gdm\_sepio\_relationships.ttl
  - PK\_proband\_score\_evidence\_line a (one of the following types)
    - gci:PREDICTED\_OR\_PROVEN\_NULL\_VARIANT 
      - `	`- hasEvidenceLineType sepio:0004079
    - `		   `- hasEvidenceItemType sepio:0004034    
    - gci:VARIANT\_IS\_DE\_NOVO
      - hasEvidenceLineType sepio:0004078
      - hasEvidenceItemType sepio:0004033 
    - gci:OTHER\_VARIANT\_TYPE\_WITH\_GENE\_IMPACT
      - hasEvidenceLineType sepio:0004080
      - hasEvidenceItemType sepio:0004035
    - gci:TWO\_VARIANTS\_WITH\_GENE\_IMPACT\_IN\_TRANS
      - hasEvidenceLineType sepio:0004019 
      - hasEvidenceItemType sepio:0004037
    - gci:TWO\_VARIANTS\_IN\_TRANS\_WITH\_ONE\_DE\_NOVO
      - hasEvidenceLineType sepio:0004018
      - hasEvidenceItemType sepio:0004038

**construct-model-systems-evidence**

- selects json data of type gci:evidenceScore
  - PK
  - caculatedScore
  - evidenceItem
  - modelSystem
  - modelSystemsType
- adds evidence lines and evidence items
  - uses modelSystemsType to match in gdm\_sepio\_relationships.ttl
    - evidenceLineType <=> modelSystemsType
    - evidenceLineType Type <=> evidenceItemType
  - sepio:0004027
    - hasEvidenceItemType sepio:0004046
      - hasGCIType :NonHumanModel
      - usedIn :ModelSystems .
  - sepio:0004028
    - hasEvidenceItemType sepio:0004047
      - hasGCIType :CellCultureModel
      - usedIn :ModelSystems

**construct-functional-evidence**

- selects json data of type gci:evidenceScore
  - PK
  - caculatedScore
  - evidenceType (values:
    - Biochemical Function
    - Case control
    - Experimental
    - Expression
    - Functional Alteration
    - Individual
    - Model Systems
    - Protein Interactions
    - Rescue
    - individual)
- adds evidence lines and evidence items
  - uses evidenceType to match in gdm\_sepio\_relationships.ttl
  - sepio:0004022 :hasEvidenceItemType sepio:0004041
  - `    		`:hasGCIType :BiochemicalFunction
  - `    		`:usedIn :Functional .
 
  - sepio:0004023 :hasEvidenceItemType sepio:0004042 ;
  - `   		 `:hasGCIType :ProteinInteraction ;
  - `   		 `:usedIn :Functional .
    
  - # gene expression
  - sepio:0004024 :hasEvidenceItemType sepio:0004043 ;
  - `    		`:hasGCIType :Expression ;
  - `    		`:usedIn :Functional .

**construct-functional-alteration-evidence**

- selects json data of type gci:evidenceScore
  - PK
  - caculatedScore
  - gci:functionalAlteration (values: 
    - Non-patient cells
    - Patient cells)
- adds evidence lines and evidenceItems
  - Uses functionalAlterationType  to match in gdm\_sepio\_relationships.ttl
  - # patient cell functional alteration
  - sepio:0004025 :hasEvidenceItemType sepio:0004044 ;
  - `    `:hasGCIType :PatientCells ;
  - `    `:usedIn :FunctionalAlteration .

  - # non-patient cell functional alteration
  - sepio:0004026 :hasEvidenceItemType sepio:0004045 ;
  - `    `:hasGCIType :NonPatientCells ;
  - `    `:usedIn :FunctionalAlteration .

**construct-rescue-evidence**

- selects json data of type gci:evidenceScore
  - PK
  - caculatedScore
  - gci:rescue
  - gci:rescueType (values:
    - Cell culture model
    - Human
    - Non-human model organism
    - Patient cells)
- adds evidence lines and evidenceItems
  - Uses rescueType  to match in gdm\_sepio\_relationships.ttl
  - # human rescue
  - sepio:0004029 :hasEvidenceItemType sepio:0004048 ;
  - `    `:hasGCIType :Human ;
  - `    `:usedIn :Rescue .
    
  - # non-human model rescue
  - sepio:0004030 :hasEvidenceItemType sepio:0004049 ;
  - `    `:hasGCIType :NonHumanModel ;
  - `    `:usedIn :Rescue .

  - #TODO Validate this works prior to rearchitecture
  - # rescue in cell culture
  - sepio:0004031 :hasEvidenceItemType sepio:0004050 ;
  - `    `:hasGCIType :CellCultureModel ;
  - `    `:usedIn :Rescue .
    
  - # rescue in patient cells
  - sepio:0004032 :hasEvidenceItemType sepio:0004051 ;
  - `    `:hasGCIType :PatientCells ;
  - `    `:usedIn :Rescue .


**construct-case-control-evidence**

- selects json data of type ?evidenceLine a gci:caseControl
  - PK
  - gci:label ?label
  - gci:studyType (values:
    - Aggregate variant analysis
    - Single variant analysis)
  - gci:date\_created 
  - gci:caseCohort
  - gci:controlCohort 
  - gci:statisticalValues
  - gci:scores
- adds evidence lines and evidenceItems
  - Uses studyType  to match in gdm\_sepio\_relationships.ttl
  - #### Case Control
  - sepio:0004020 :hasEvidenceItemType sepio:0004039 ;
  - `    `:hasGCIType :SingleVariantAnalysis .
    
  - sepio:0004021 :hasEvidenceItemType sepio:0004040 ;
  - `    `:hasGCIType :AggregateVariantAnalysis .
- Evidence items indicated by PK\_\_cc\_evidence\_item

**construct-segregation-evidence**

- selects json data of type  gci:family
  - PK
  - gci:segregation
  - gci:label
- adds type sepio/FamilyCosegregation
  - adds PK\_segregation

**construct-alleles**

- selects json data of type gci:variant
- adds type ga4gh:VariationDescriptor

**construct-articles**

- selects json data of type gci:article ;
  - PK
  - gci:title
  - gci:authors 
  - gci:date
  - gci:pmid
- adds type dc:BibliographicResource 

**construct-secondary-contributions**

- select json data of type  gci:provisionalClassification
  - PK
  - `  `gci:approvedClassification true ;
  - `  `gci:classificationContributors
- adds
  - :sepio/qualified-contribution \_:contrib .
  - :contrib :sepio/has-agent ?secondaryContributor ;
  - :bfo/realizes :sepio/SecondaryContributorRole

**construct-variant-score**

- selects json data of type gci:variantScore
  - PK
  - gci:variantType (values
    - OTHER\_VARIANT\_TYPE
    - PREDICTED\_OR\_PROVEN\_NULL)
  - `  `gci:variantScored
  - `  `gci:deNovo
  - `  `gci:scoreStatus
  - `  `gci:calculatedScore
  - `  `gci:date\_created
- adds evidence lines and evidence items
  - uses the variantType to match in gdm\_sepio\_relationships.ttl
  - # Variant types

  - gci:PREDICTED\_OR\_PROVEN\_NULL :hasEvidenceLineType sepio:0004120 ;
  - `    `:hasEvidenceItemType sepio:0004117 .

  - gci:OTHER\_VARIANT\_TYPE :hasEvidenceLineType sepio:0004121 ;
  - `    `:hasEvidenceItemType sepio:0004118 .

**construct-unscoreable-evidence**

- selects json data of type gci:annotation 
  - PK
  - `  `gci:article / gci:pmid 
  - `  `gci:articleNotes / gci:nonscorable /gci:text 
- adds evidence lines of type :sepio/UnscoreableEvidenceLine (SEPIO\_0004127)
  - evidenceItems as PK\_evidence\_item

**construct-evidence-connections**

- selects all of type :gcixform/hasEvidenceLineType from gdm\_sepio\_relationships.ttl
  - all lines in the current graph with any of those evidenceLineTypes
  - and all lines in the current graph with types of the criterionAssessment
  - and links them via :sepio/has-evidence

