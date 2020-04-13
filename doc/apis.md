# APIs and code organization overview

* RDF query and manipulation (genegraph.database)
  * RDF Parsing and Storage (genegraph.database.load)
  * RDF Query and inspection (genegraph-database.query)
  * Transaction Handling (genegraph.database.util)
* Transformation into RDF (genegraph.transform.core)
* Connecting data sources to database (genegraph.sink)
  * Collect and import base state from original sources (genegraph.sink.base)
  * Establish and maintain state through connection to Apache Kafka (genegraph.sink.stream)
