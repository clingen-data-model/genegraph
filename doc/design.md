# Design and Architecture

## Goal

This application is intended to synthesize data from various sources: gene information (HGNC), disease information (OMIM, MONDO, HPO),  and curated information from ClinGen (Gene Validity, Actionability, Gene Dosage), and present it with APIs useful for presentation to viewers. The supporting data, particularly the curated information, is structurally complex; there is a need to support multiple versions, with multiple distinct data models simultaneously. The goal of this software is to provide an interface to this data that does not require viewers to cope with the underlying complexity of the data that can be used easily from front-end applications.

## Design

### Environment ####

The data service exists within the ClinGen Architecture, and relies on two important elements to provide its functionality: the ClinGen Streaming Service (Apache Kafka), and the SEPIO model for ClinGen Curations.

#### Streaming Service ####

All data in the data service are synthesized from external sources; the base data is supported by outside authorities, while the ClinGen curations are constructed from data built from the streaming service. This service does not durabily persist data, durable persistence is provided by layers outside this application, especially the streaming service. It should always be possible to reconstruct the state of the data by reading the base data, then reading the streams of curations provided by the streaming service.

#### SEPIO Model ####

Because the data service is required to present data on a variety of topics, with multiple of versions and structures within those topics, a data model that identifies common elements between these topics and presents them in a semantically consistent way is an invaluable tool. In ClinGen, the SEPIO-based data models fill this role. Ideally, producers of data are contract-bound to provide data in the model format for their particular domain, which are validated and consumed via the streaming service by this application. This not always being the case, facilities for transforming input data are included in this application.

### Data

#### Database requirements 

* The data that this service recieves may arrive in a range of different structures; there is a need to present the data with the structure it was first recieved in. This makes writing migrations to coerce the data into a rigorously structured format, such as a RDBMS schema, somewhat hazardous, since there is a risk of losing the original intent of the authors over time. 
* There is a desire to make the data accessible and queryable by top-level associations (such as a gene or disease linked to a curation), as well as associations at a much lower level (such as a publication used as evidence supporting the relevance of a gene to a disease).
* Much of the supporting data is graph-structured, such as the MONDO disease ontology, and the Human Phenotype Ontology (HPO). It is strongly preferable to query such data with graph semantics.
* There is a commitment across ClinGen to provide our data as linked, open data in RDF, using JSON-LD.
* The size of the data we are working with is not particularly large, in the tens to hundreds of gigabytes, well within the range that fits within disk (and even main memory) of a single computer.
* Because the data is loosely structured, being able to interrogate the data and decide on codepaths as the data is being read is highly desirable. It is difficult to declare all the possible structure for the underlying data, as in an SQL query.

These factors make an RDF triplestore a natural choice for data storage and access. The most important data sources are already in RDF, the format supports schemaless data, while permitting query of embedded data elements without requiring knowledge of the structure of the incoming data, as long as appropriate conventions are followed. Embedded options are available for RDF triplestores, allowing the data to be read and queried with the performance of an in-memory graph, rather than a remote database.

Apache Jena TDB2, running in embedded mode, was selected to meet these requirements. Because the data is read through the Jena Dataset interface, any underlying store that supports this interface (including a remotely accessed network database) can be plugged in without code modification, though other stores may not support the expected level of performance.

### Service design

Multiple instances of the data service can be stood up and expected to maintain relatively consistent state. Each will have its own Jena TDB2 instance and local data store, but all rely on the same data stream from Apache Kafka.

Each instance is expected to have its own data store. Instance-local SSD storage is the most appropriate format for this data store, as persistence and durability are not requirements of this application, but performance could be adversely affected by storage with long seek times or network contention.

