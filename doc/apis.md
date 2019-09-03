# APIs and code organization overview

* RDF query and manipulation (clingen-search.database)
  * RDF Parsing and Storage (clingen-search.database.load)
  * RDF Query and inspection (clingen-search-database.query)
  * Transaction Handling (clingen-search.database.util)
* Transformation into RDF (clingen-search.transform.core)
* Connecting data sources to database (clingen-search.sink)
  * Collect and import base state from original sources (clingen-search.sink.base)
  * Establish and maintain state through connection to Apache Kafka (clingen-search.sink.stream)
