# Genegraph Developer Notes

## Project environment variables
`GENEGRAPH_DATA_PATH`="data" - data directory path<br>
`GENEGRAPH_DATA_VERSION`="2020-08-06T2248" - data version of database to load<br>
`SERVEUR_KEY_PASS`= "..." password to keystore/trustore<br>
`SERVEUR_KEYSTORE`="keys/dev.serveur.keystore.jks"<br>
`SERVEUR_TRUSTSTORE`="keys/serveur.truststore.jks"<br>
`CG_SEARCH_TOPICS`="gene-dosage-stage;actionability;gci-legacy" - list of topics to process<br>
`DX_STAGE_JAAS`="org.apache.kafka.common.security.plain.PlainLoginModule required username='...' password='...';" - confluent cloud connection string<br>
`GENEGRAPH_BUCKET`="genegraph-dev" - gs bucket containing database versions<br>
`GENEGRAPH_VALIDATE_EVENTS`=true - enable shacl validation of stream events<br>
`GENEGRAPH_GQL_CACHE`=true - enable/disable caching<br>
`GENEGRAPH_MIGRATION_VERSION`="2020-07-07T1234" - When defined, and
the date represents a previous migration archive (either in a gcs bucket or
locally), represents the migration from
where base data will pulled when creating a new migration. This is
instead of pulling from the internet URLs defined in base.edn.

## Project directory structure

### Root level files

Dockerfile - Docker build file<br>
config - contains logback.xml file for configuring logging<br>
deploy - don't believe this is still used. Use files in the architecture project instead<br>
doc - Outdated but useful docs<br>
docs - generated docs<br>
jars - non-production jar files<br>
keys - data exchange key files<br>
logs - application log files<br>
project.clj - Leiningen project configuration file<br>
resources - resources directory<br>
run - Dunno<br>
src - Project source code<br>
test - Project test code<br>
version.edn - migration version file<br>

### Src/genegrapgh directory

├── admin.clj<br>
├── annotate<br>
│   └── gene.clj<br>
├── annotate.clj - *annotation of stream events*<br>
├── database - *triple store database loading querying etc.*<br>
│   ├── admin.clj<br>
│   ├── datafy.clj<br>
│   ├── instance.clj<br>
│   ├── jsonld.clj<br>
│   ├── load.clj<br>
│   ├── names.clj<br>
│   ├── query<br>
│   │   ├── algebra.clj<br>
│   │   ├── resource.clj<br>
│   │   └── types.clj<br>
│   ├── query.clj<br>
│   ├── util.clj<br>
│   └── validation.clj<br>
├── env.clj - *environment variable processing*<br>
├── migration.clj - *triplestore database migration processing*<br>
├── response_cache.clj - *response level caching code*<br>
├── rocksdb.clj - *rocks db code*<br>
├── server.clj - *main genegraph entry point*<br>
├── service.clj - *web service level code*<br>
├── sink - *sink processing code*<br>
│   ├── base.clj<br>
│   ├── batch.clj<br>
│   ├── event.clj<br>
│   ├── fetch.clj<br>
│   └── stream.clj<br>
├── source - *source processing code*<br>
│   ├── cache.clj <br>
│   ├── graphql - *graphql resolvers*<br>
│   │   ├── actionability.clj<br>
│   │   ├── affiliation.clj<br>
│   │   ├── common<br>
│   │   │   ├── cache.clj<br>
│   │   │   ├── curation.clj<br>
│   │   │   └── enum.clj<br>
│   │   ├── condition.clj<br>
│   │   ├── coordinate.clj<br>
│   │   ├── core.clj<br>
│   │   ├── dosage_proposition.clj<br>
│   │   ├── drug.clj<br>
│   │   ├── evidence.clj<br>
│   │   ├── gene.clj<br>
│   │   ├── gene_dosage.clj<br>
│   │   ├── gene_feature.clj<br>
│   │   ├── gene_validity.clj<br>
│   │   ├── genetic_condition.clj<br>
│   │   ├── region_feature.clj<br>
│   │   ├── resource.clj<br>
│   │   ├── server_status.clj<br>
│   │   └── suggest.clj<br>
│   ├── html - *legacy not used*<br>
│   │   ├── common.clj<br>
│   │   ├── elements<br>
│   │   │   ├── class.clj<br>
│   │   │   ├── domain_model.clj<br>
│   │   │   ├── dosage_sensitivity_proposition.clj<br>
│   │   │   ├── evidence_level_assertion.clj<br>
│   │   │   ├── functional_copy_number_complement.clj<br>
│   │   │   ├── genes.clj<br>
│   │   │   ├── genetic_dosage.clj<br>
│   │   │   ├── node_shape.clj<br>
│   │   │   ├── study_finding.clj<br>
│   │   │   └── value_set.clj<br>
│   │   └── elements.clj<br>
│   └── json - *legacy not used*<br>
│       └── common.clj<br>
├── suggest - *suggester (type ahead) code*<br>
│   ├── infix_suggester.clj<br>
│   ├── serder.clj<br>
│   └── suggesters.clj<br>
├── transform - *transformations for loading data into triple store*<br>
│   ├── actionability.clj<br>
│   ├── affiliations.clj<br>
│   ├── common_score.clj<br>
│   ├── core.clj<br>
│   ├── features.clj<br>
│   ├── gci.clj<br>
│   ├── gci_express.clj<br>
│   ├── gci_legacy.clj<br>
│   ├── gci_neo4j.clj<br>
│   ├── gene.clj<br>
│   ├── gene_validity<br>
│   │   ├── construct_case_control_evidence.sparql<br>
│   │   ├── construct_evidence_connections.sparql<br>
│   │   ├── construct_evidence_level_assertion.sparql<br>
│   │   ├── construct_functional_alteration_evidence.sparql<br>
│   │   ├── construct_functional_evidence.sparql<br>
│   │   ├── construct_model_systems_evidence.sparql<br>
│   │   ├── construct_proband_score.sparql<br>
│   │   ├── construct_proposition.sparql<br>
│   │   ├── construct_rescue_evidence.sparql<br>
│   │   ├── construct_segregation_evidence.sparql<br>
│   │   ├── five_genes.sparql<br>
│   │   └── gdm_sepio_relationships.ttl<br>
│   ├── gene_validity.clj<br>
│   ├── hi_index.clj<br>
│   ├── loss_intolerance.clj<br>
│   ├── omim.clj<br>
│   └── rxnorm.clj<br>
└── util<br>
    └── gcs.clj


## resources directory

`base.edn` - file describing bse data to load into triple store<br>
`base_test.edn` -<br>
`batch-events.edn` -<br>
`class-names.edn` - rdf class names<br>
`formats.edn` - file for specifying how to process event streams<br>
`genegraph-assembly.ttl` - pre-converted jena assembly file<br>
`graphql-schema.edn` - graphql api specification file<br>
`graphql_schema.edn` -<br>
`kafka.edn` - details of various kafka event processing<br>
`namespaces.edn` - rdf namespaces<br>
`property-names.edn` - rdf properties<br>
`public` -<br>
`queries` - some canned queries<br>
`shapes.edn` - file associating shacl shapes to event classes<br>
`test_data` - test data files<br>
`version.edn` - another migration version file<br>

## Database directory contents

`base` - base data files loaded into trile store<br>
`genegraph-assembly.ttl` - apache jena assembly file<br>
`lucene_index` - text search indices integrated into jena <br>
`partition_offsets.edn` - kafka topic offsets<br>
`suggestions` - type ahead suggester indicies<br>
`tdb` - apache jena triple store db files<br>

## Building

lein clean (optional)<br>
lein uberjar<br>
lein run<br>

## Access to GraphiQL

lein run<br>
http://localhost:8888<br>

## Starting a REBL

In the REPL:
(use 'cognitect.rebl)
(ui)

