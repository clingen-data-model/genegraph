# First iteration and lessons learned

The first version of ClinGen search (beyond the ProcessWire derived interface) was built on Ruby on Rails using a neo4j backend. For us, Neo4j was a fairly new technology, selected because we knew we'd be working with graph-structured ontological data, and prior experience trying to integrate that into a relational database was painful. Neo4j was used by the folks who developed the most sophisticated ontologies we needed to use (Monarch Initiative). Additionally, there appeared to be a reasonably mature integration with Ruby and Rails (neo4jrb). Meanwhile Rails was a familiar and fast way to get up and running with a working web application.

TODO: work in a description of 

Shortly prior to this project kicking off, I'd picked up Clojure for the first time. I needed to load the data from these ontologies into Neo4j, and a few things made me not want to do this in the Ruby on Rails app. Ruby can be painful at processing large chunks of data, library support for the type of data we're using (RDF/OWL) is strongest on the Java platform, and jRuby doesn't have the most elegant Java interoperability, even if it's a pretty decent piece of software in it's own right. I was poking around for a language and environment that could do large(er) scale data processing coupled with good Java interop, without, you know, being Java. Scala and Clojure had enough buzz for me to be aware of them. Of the two, Clojure was a Lisp, and I had a good time writing Common Lisp for an AI course I'd take a few years ago, so that was enough reason to give Clojure a go.

That piece of the code worked pretty well. It was of reasonable scope for kicking the tires on a new language. The Java interop on Clojure was delightful to work with after JRuby. The OWLAPI Java library was a bit of a pain to work with, but Neo4j was great. In the end, it all worked quickly and well.

The Rails web app came along well enough. The integration with Neo4j came with a significant learning curve. Neo4jrb comes with an Object Graph Mapper library, similar to an Object Relational Mapper. This proved to be of less utility than one would have liked. One of the important tricks with an ORM is to specify what related data is going to be used in the initial SELECT query (however that gets formed) so that you don't wind up with n * n ( * n ... ) queries just to track down the data you need to write out a list. The Rails ORM makes this magic work most of the time. The Neo4j library did not. The only performant way to do this was to skip the OGM and use Cypher queries to build a projection of the desired graph into a JSON-like structure. This approach worked, but required large, bespoke queries for most every page, which was at least partially the reason why OGMs and their ilk existed in the first place.

In the meantime, the source data for this project is increasingly structured as RDF in JSON-LD form. This is an entity-attribute-value format where entities and attributes are specified using URIs, essentially giving everything a global namespace when used correctly. The explicit goal of this is to allow datasets collected from disparate sources to be combined effectively. This is something we do a lot of in the life sciences. While this structure translates nicely into a property graph database like Neo4J, there are RDF-native databases that can load and translate RDF data in its native format. By keeping the data in its native format, we can avoid errors in translation.

We'd also built a few more components using Clojure, and at least one of us on the team (me) was beginning to internalize the Clojure way of doing things. When I'd first tackled Clojure, it was as a Lisp that could Java. This alone is great, but there's a lot more to the design of the language than this. (TODO, review Rich Hickey talks) Once you've internalized these concepts, it's hard to look at an ORM and think it's a sensible design choice.

From the previous version of the site we'd learned:

* There's value in storing RDF data as RDF
* Avoiding n*n query patterns is hard

What we want is a library that allows us to traverse the graph that represents the information in our database as easily as any other data structure in Clojure. Ideally the data is stored as native RDF. We add some functionality on top of this that allows us to query the data using human friendly names (not using RDF URIs), SPARQL is used for queries to select the initial set of nodes to work with, as well as to create subgraphs when one wants to limit the set of data available to traverse.

We aren't building this system from whole cloth. Apache Jena exists as an RDF API, with capability for reasoning with OWL, a persistent triplestore with transaction support, and a SPARQL engine. Its API is Java--enough that one would prefer not to use it directly. Building a translation layer also allows us to boil things down to the core functions of querying a graph (via SPARQL) and traversing a graph (via data.xml-like traversal) so doing building a layer of indirection around the actual graph library (which might change).






