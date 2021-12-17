# Transforming Genegraph

## Overview

Transforming genegraph is a version of genegraph that when configured correctly, consumes kafka streams for the purpose of  transforming the data on those streams into a SEPIO format and publishing that data to streams meant to be the source of record for that data. This is part of an architectural shift from the previous "all-in-one" genegraph that transformed streams and made data available via the web, to separate components for 1) a transforming genegraph and 2) one or more web-serving genegraphs. 

## Configuration

### Transforming Genegraph configuration

To enable the functionality of a transforming genegraph, the `GENEGRAPH_MODE` environment variable must be set to "transformer". The result of this setting results in:
- The pedestal web server enabling only the "/ready", "/live" and "/env" endpoints.
- The lacinia graphql interceptor chains are not included in the configuration (i.e. no graphql endpoint is available).
- The stream event processing interceptor chain enables a KafkaProducer for publishing messages to kafka topics when properly configured as detailed below.

### Kafka Cluster configuration
The :clusters map in kafka.edn defines the kafka cluster configurations for all topics consumed and produced. Each :cluster map entry now has 3 separate maps for configuring the kafka configurations for each cluster: common, consumer and producer. This alows for separate configuration of consumers and producers through configuration. In particular, the [KafkaPublisher] (https://kafka.apache.org/25/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html) allows for different producer message delivery semantics enabled through configuration:
- :common - the common kafka configuration information shared by both consumers and publishers.
- :consumer - consumer specific configuration.
- :producer - producer specific configuration.
Example cluster configuration: 
    `{:clusters {:dx-ccloud {:common { "ssl.endpoint.identification.algorithm" "https"
                                      "sasl.mechanism" "PLAIN"
                                      "request.timeout.ms" "20000"
                                      "bootstrap.servers" "*******"
                                      "retry.backoff.ms" "500"
                                      "security.protocol" "SASL_SSL"
                                      "sasl.jaas.config" "******"}
                            :consumer {"key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                                       "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"}
                            :producer {"key.serializer" "org.apache.kafka.common.serialization.StringSerializer"
                                       "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"}}}`
									   
### Topic configuration
- `CG_SEARCH_TOPICS` contains the list of topics to be consumed.
-  Each topic in the kafka.edn :topics map may define a :producer-topic key followed by the stream to which transformed messages will be witten. This represents a consumer-to-producer topic mapping.

Example consumer-to-producer topic configuration:
    `:gene-dosage-jira {:name "gene_dosage_raw"
                        :format :gene-dosage-jira
                        :root-type :sepio/GeneDosageReport
                        :cluster :dx-ccloud
                        :producer-topic :test-public-v1}`

## Changes to consider

- Only consume streams that have a :producer-topic defined in kafka.edn :topics map, that also intersect with topics defined in `CG_SEARCH_TOPICS`. Right now the topics defined in `CG_SEARCH_TOPICS` are processed whether or not they have a :producer-topic defined.
