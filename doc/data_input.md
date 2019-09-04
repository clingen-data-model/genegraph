# Data Input
(clingen-search.sink, clingen-search.transform)

The data service takes data from predefined input sources and presents it via an API. This process occurs in the clingen-search.sink namespace and the clingen-search.transform namespace.

## Document definition

Some amount of metadata is required to process input data and store it in the database. Each file and stream has a short associated description with the following fields.

* **name** *required unless root-type exists* The graph name the document will be stored with
* **source** *required for base docs* The URL the document may be downloaded from
* **target** *required for base docs* The filename to store the document in
* **format** *required* Specifies the transformer to use to read the document
* **reader-opts** *optional* Specifies additional options to pass when reading the document, such as the RDF serialization to expect
* **root-type** *required unless name exists* The type of the node containing the document name. Only one node of such type should exist in any document.

For example, the doc def for the core RDF syntax in resources/base.edn
```
{:name "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  :source "http://www.w3.org/1999/02/22-rdf-syntax-ns.ttl",
  :target "rdf.ttl",
  :format :rdf
  :reader-opts {:format :turtle}}
```

And the doc def for pre-SEPIO actionability curations from the streaming service
```
{:format :actionability-v1
:root-type :sepio/ActionabilityReport}
```

## Transforming input data
(clingen-search.transform.core)

The data service is designed to import and make accessible data in RDF form. That said, not all the data we need is accessible in this format. In order to use this data, we need to define a transformation that extracts the important elements into RDF. The code provides a framework for doing this via the [transform-doc](#) multimethod in clingen-search.transform.core.

This method expects a map with a :format key, and either a :target key in the map defining the document's location (used when importing the base data after it's already been downloaded), or a second argument containing the document itself (usually in a string, but this is not a requirement.)

## Base Data
(clingen-search.sink.base)

The [initialize-db!](#) function in this namespace is run whenever the service is started. It downloads any of the documents defined in resources/base.edn that have not already been downloaded (using $DATA_DIR/base_state.edn as a reference). It then imports each of these into the database, passing them through the transformation function defined for the specified :format.

## Streaming data
(clingen-search.sink.stream)

The software opens a single-threaded connection to the streaming service, using the CG_SEARCH_TOPICS environment variable to select the topics to subscribe to. All offsets are maintained locally (in $CG_SEARCH_DATA_VOL/partition_offsets.edn), and not on the Kafka server. 

This namespace also includes functions for retrieving data from topics and storing them either to disk or to local vars, which is helpful when developing at the REPL

