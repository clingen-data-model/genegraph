(ns genegraph.source.graphql.core
  (:require [genegraph.database.query :as q]
            [genegraph.database.util :refer [tx]]
            [genegraph.source.graphql.gene :as gene]
            [genegraph.source.graphql.evidence-line :as evidence-line]
            [genegraph.source.graphql.evidence-item :as evidence-item]
            [genegraph.source.graphql.assertion :as assertion]
            [genegraph.source.graphql.resource :as resource]
            [genegraph.source.graphql.actionability :as actionability]
            [genegraph.source.graphql.actionability-assertion :as ac-assertion]
            [genegraph.source.graphql.gene-validity :as gene-validity]
            [genegraph.source.graphql.gene-dosage :as gene-dosage]
            [genegraph.source.graphql.gene-feature :as gene-feature]
            [genegraph.source.graphql.region-feature :as region-feature]
            [genegraph.source.graphql.coordinate :as coordinate]
            [genegraph.source.graphql.dosage-proposition :as dosage-proposition]
            [genegraph.source.graphql.contribution :as contribution]
            [genegraph.source.graphql.condition :as condition]
            [genegraph.source.graphql.server-status :as server-status]
            [genegraph.source.graphql.evidence :as evidence]
            [genegraph.source.graphql.genetic-condition :as genetic-condition]
            [genegraph.source.graphql.affiliation :as affiliation]
            [genegraph.source.graphql.suggest :as suggest]
            [genegraph.source.graphql.drug :as drug]
            [genegraph.source.graphql.mode-of-inheritance :as mode-of-inheritance]
            [genegraph.source.graphql.criteria :as criteria]
            [genegraph.source.graphql.classification :as classification]
            [genegraph.source.graphql.user :as user]
            [genegraph.source.graphql.group :as group]
            [genegraph.source.graphql.clinvar.clinical_assertion :as cv-clinical-assertion]
            [genegraph.source.graphql.clinvar.aggregate_assertion :as cv-aggregate-assertion]
            [genegraph.source.graphql.clinvar.contribution :as cv-contribution]
            [genegraph.source.graphql.clinvar.variant :as cv-variant]
            [genegraph.source.graphql.clinvar.gene :as cv-gene]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [genegraph.source.graphql.common.curation :as curation]))

(defn resolver-map []
  {:clinvar/clinical-assertion-single cv-clinical-assertion/clinical-assertion-single
   :clinvar/clinical-assertion-subject cv-clinical-assertion/subject
   :clinvar/clinical-assertion-predicate cv-clinical-assertion/predicate
   :clinvar/clinical-assertion-object cv-clinical-assertion/object
   :clinvar/clinical-assertion-version cv-clinical-assertion/version
   :clinvar/clinical-assertion-list cv-clinical-assertion/clinical-assertion-list
   :clinvar/clinical-assertion-contribution cv-clinical-assertion/contribution
   :clinvar/clinical-assertion-review-status cv-clinical-assertion/review-status
   :clinvar/clinical-assertion-date-updated cv-clinical-assertion/date-updated
   :clinvar/clinical-assertion-release-date cv-clinical-assertion/release-date
   :clinvar/clinical-assertion-version-of cv-clinical-assertion/version-of
   :clinvar/clinical-assertion-allele-origin cv-clinical-assertion/allele-origin
   :clinvar/clinical-assertion-collection-method cv-clinical-assertion/collection-method
   :clinvar/clinical-assertion-classification-context cv-clinical-assertion/classification-context
   :clinvar/aggregate-assertion-list cv-aggregate-assertion/aggregate-assertion-list
   :clinvar/aggregate-assertion-version-of cv-aggregate-assertion/version-of
   :clinvar/aggregate-assertion-release-date cv-aggregate-assertion/release-date
   :clinvar/aggregate-assertion-review-status cv-aggregate-assertion/review-status
   :clinvar/aggregate-assertion-subject cv-aggregate-assertion/subject
   :clinvar/aggregate-assertion-predicate cv-aggregate-assertion/predicate
   :clinvar/aggregate-assertion-object cv-aggregate-assertion/object
   :clinvar/aggregate-assertion-version cv-aggregate-assertion/version
   :clinvar/aggregate-assertion-members cv-aggregate-assertion/members
   :contribution/contribution-query cv-contribution/contribution-single
   :contribution/agent cv-contribution/agent
   :contribution/agent-role cv-contribution/agent-role
   :contribution/activity-date cv-contribution/activity-date
   :variant/variant-single cv-variant/variant-single
   :variant/name cv-variant/variant-name
   :variant/genes cv-variant/variant-genes
   :variant/id cv-variant/variant-id
   :clinvar/gene-list cv-gene/gene-list
   :clinvar/gene-preferred-label cv-gene/gene-preferred-label
   :clinvar/gene-symbol cv-gene/gene-symbol

   :actionability/actionability-query actionability/actionability-query
   :actionability/classification-description actionability/classification-description
   :actionability/conditions actionability/conditions
   :actionability/report-date actionability/report-date
   :actionability/report-id actionability/report-id
   :actionability/source actionability/source
   :actionability/wg-label actionability/wg-label
   :actionability/tot-actionability-reports actionability/tot-actionability-reports
   :actionability/tot-actionability-updated-reports actionability/tot-actionability-updated-reports
   :actionability/tot-gene-disease-pairs actionability/tot-gene-disease-pairs
   :actionability/tot-adult-gene-disease-pairs actionability/tot-adult-gene-disease-pairs
   :actionability/tot-pediatric-gene-disease-pairs actionability/tot-pediatric-gene-disease-pairs
   :actionability/tot-outcome-intervention-pairs actionability/tot-outcome-intervention-pairs
   :actionability/tot-adult-outcome-intervention-pairs actionability/tot-adult-outcome-intervention-pairs
   :actionability/tot-pediatric-outcome-intervention-pairs actionability/tot-pediatric-outcome-intervention-pairs
   :actionability/tot-adult-failed-early-rule-out actionability/tot-adult-failed-early-rule-out
   :actionability/tot-pediatric-failed-early-rule-out actionability/tot-pediatric-failed-early-rule-out
   :actionability/tot-adult-score-counts actionability/tot-adult-score-counts
   :actionability/tot-pediatric-score-counts actionability/tot-pediatric-score-counts
   :actionability/statistics-query actionability/statistics-query
   :ac-assertion/report-date ac-assertion/report-date
   :ac-assertion/source ac-assertion/source
   :ac-assertion/classification ac-assertion/classification
   :ac-assertion/attributed-to ac-assertion/attributed-to
   :ac-assertion/report-label ac-assertion/report-label
   :affiliation/affiliation-query affiliation/affiliation-query
   :affiliation/affiliations affiliation/affiliations
   :affiliation/curated-diseases affiliation/curated-diseases
   :affiliation/curated-genes affiliation/curated-genes
   :affiliation/gene-validity-assertions affiliation/gene-validity-assertions
   :assertion/subject assertion/subject
   :assertion/predicate assertion/predicate
   :assertion/object assertion/object
   :assertion/evidence-lines assertion/evidence-lines
   :assertion/subject-of assertion/subject-of
   :classification/classifications classification/classifications
   :condition/condition-query condition/condition-query
   :condition/curation-activities condition/curation-activities
   :condition/description condition/description
   :condition/direct-subclasses condition/direct-subclasses
   :condition/direct-superclasses condition/direct-superclasses
   :condition/disease-list condition/disease-list
   :condition/diseases condition/diseases
   :condition/genetic-conditions condition/genetic-conditions
   :condition/last-curated-date condition/last-curated-date
   :condition/subclasses condition/subclasses
   :condition/superclasses condition/superclasses
   :condition/synonyms condition/synonyms
   :condition/propositions condition/propositions
   :gv-contribution/agent contribution/agent
   :gv-contribution/realizes contribution/realizes
   :gv-contribution/date contribution/date
   :coordinate/assembly coordinate/assembly
   :coordinate/build coordinate/build
   :coordinate/chromosome coordinate/chromosome
   :coordinate/end-pos coordinate/end-pos
   :coordinate/start-pos coordinate/start-pos
   :coordinate/strand coordinate/strand
   :criteria/criteria criteria/criteria
   :dosage-proposition/assertion-type dosage-proposition/assertion-type
   :dosage-proposition/classification-description dosage-proposition/classification-description
   :dosage-proposition/comments dosage-proposition/comments
   :dosage-proposition/disease dosage-proposition/disease
   :dosage-proposition/dosage-classification dosage-proposition/dosage-classification
   :dosage-proposition/evidence dosage-proposition/evidence
   :dosage-proposition/gene dosage-proposition/gene
   :dosage-proposition/phenotypes dosage-proposition/phenotypes
   :dosage-proposition/report-date dosage-proposition/report-date
   :dosage-proposition/score dosage-proposition/score
   :dosage-proposition/wg-label dosage-proposition/wg-label
   :drug/aliases drug/aliases
   :drug/drug-query drug/drug-query
   :drug/drugs drug/drugs
   :evidence/description evidence/description
   :evidence/source evidence/source
   :evidence-line/evidence-items evidence-line/evidence-items
   :evidence-line/score evidence-line/score
   :evidence-item/evidence-lines evidence-item/evidence-lines
   :gene/chromosome-band gene/chromosome-band
   :gene/conditions gene/conditions
   :gene/curation-activities gene/curation-activities
   :gene/dosage-curation gene/dosage-curation
   :gene/gene-validity-assertions gene/gene-validity-assertions
   :gene/gene-list gene/gene-list
   :gene/gene-query gene/gene-query
   :gene/genes gene/genes
   :gene/hgnc-id gene/hgnc-id
   :gene/last-curated-date gene/last-curated-date
   :gene-dosage/dosage-list-query gene-dosage/dosage-list-query
   :gene-dosage/gene-count gene-dosage/gene-count
   :gene-dosage/gene-dosage-query gene-dosage/gene-dosage-query
   :gene-dosage/genomic-feature gene-dosage/genomic-feature
   :gene-dosage/haplo gene-dosage/haplo
   :gene-dosage/haplo-index gene-dosage/haplo-index
   :gene-dosage/label gene-dosage/label
   :gene-dosage/location-relationship gene-dosage/location-relationship
   :gene-dosage/morbid gene-dosage/morbid
   :gene-dosage/morbid-phenotypes gene-dosage/morbid-phenotypes
   :gene-dosage/omim gene-dosage/omim
   :gene-dosage/pli-score gene-dosage/pli-score
   :gene-dosage/region-count gene-dosage/region-count
   :gene-dosage/report-date gene-dosage/report-date
   :gene-dosage/total-count gene-dosage/total-count
   :gene-dosage/totals-query gene-dosage/totals-query
   :gene-dosage/triplo gene-dosage/triplo
   :gene-dosage/wg-label gene-dosage/wg-label
   :gene-feature/alias-symbols gene-feature/alias-symbols
   :gene-feature/chromosomal-band gene-feature/chromosomal-band
   :gene-feature/coordinates gene-feature/coordinates
   :gene-feature/function gene-feature/function
   :gene-feature/gene-type gene-feature/gene-type
   :gene-feature/hgnc-id gene-feature/hgnc-id
   :gene-feature/locus-type gene-feature/locus-type
   :gene-feature/previous-symbols gene-feature/previous-symbols
   :gene-validity/attributed-to gene-validity/attributed-to
   :gene-validity/classification gene-validity/classification
   :gene-validity/contributions  gene-validity/contributions
   :gene-validity/disease gene-validity/disease
   :gene-validity/gene gene-validity/gene
   :gene-validity/gene-validity-assertion-query gene-validity/gene-validity-assertion-query
   :gene-validity/gene-validity-curations gene-validity/gene-validity-curations
   :gene-validity/gene-validity-list gene-validity/gene-validity-list
   :gene-validity/legacy-json gene-validity/legacy-json
   :gene-validity/mode-of-inheritance gene-validity/mode-of-inheritance
   :gene-validity/report-date gene-validity/report-date
   :gene-validity/specified-by gene-validity/specified-by
   :gene-validity/has-format gene-validity/has-format
   :gene-validity/report-id gene-validity/report-id
   :gene-validity/animal-model gene-validity/animal-model
   :genetic-condition/actionability-curations genetic-condition/actionability-curations
   :genetic-condition/actionability-assertions genetic-condition/actionability-assertions
   :genetic-condition/disease genetic-condition/disease
   :genetic-condition/gene genetic-condition/gene
   :genetic-condition/gene-dosage-curation genetic-condition/gene-dosage-curation
   :genetic-condition/gene-validity-curation genetic-condition/gene-validity-curation
   :genetic-condition/mode-of-inheritance genetic-condition/mode-of-inheritance
   :group/groups group/groups
   :mode-of-inheritance/modes-of-inheritance mode-of-inheritance/modes-of-inheritance
   :region-feature/chromosomal-band region-feature/chromosomal-band
   :region-feature/coordinates region-feature/coordinates
   :resource/alternative-label resource/alternative-label
   :resource/curie resource/curie
   :resource/iri resource/iri
   :resource/label resource/label
   :resource/website-display-label resource/website-display-label
   :resource/type resource/type
   :server-status/migration-version server-status/migration-version
   :server-status/server-version-query server-status/server-version-query
   :suggest/curations suggest/curations
   :suggest/curie suggest/curie
   :suggest/alternative-curie suggest/alternative-curie
   :suggest/highlighted-text suggest/highlighted-text
   :suggest/iri suggest/iri
   :suggest/suggest suggest/suggest
   :suggest/suggest-type suggest/suggest-type
   :suggest/text suggest/text
   :suggest/weight suggest/weight
   :user/user-query user/user-query
   :user/current-user user/current-user
   :user/email user/email
   :user/is-admin user/is-admin
   :user/member-of user/member-of}) 

(defn schema []
  (-> (io/resource "graphql-schema.edn")
      slurp
      edn/read-string
      (util/attach-resolvers (resolver-map))
      schema/compile))

(defn schema-for-merge
  "Return the schema map for later compilation based on the schema to merge
  with the new, model based schema."
  []
  (-> (io/resource "graphql-schema-for-merge.edn")
      slurp
      edn/read-string
      (util/attach-resolvers (resolver-map))))

(defn gql-query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  ([query-str]
   (tx (lacinia/execute (schema) query-str nil nil)))
  ([query-str variables]
   (tx (lacinia/execute (schema) query-str variables nil))))

