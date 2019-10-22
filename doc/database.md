# Database search and retrieval
(genegraph.database.query)

This namespace provides read access to the underlying triplestore. It provides functions that allow one to select a single node as a starting point for traversal using an IRI [resource](#) or a set of nodes to use as a starting point using a SPARQL query [select](#). Traversal can be accomplished using the [ld->](#) and [ld1->](#) functions, as well as standard Clojure hash access [get](#). This namespace wraps the underlying Jena Resource object in a Clojure type [RDFResource](#), which extends the Clojure [ISeq](#) interface, providing hash-like access to elements in the knowledge graph.

## Keyword aliases

IRIs are used as identifiers in RDF. This (usually) makes them globally unique, however they are often long and cumbersome. In particular, many of the identifiers we use have serial numbers, rather than human-readable names (such as all the property descriptions in SEPIO). In code, we prefer to use identifiers that are namespaced and have human-readable names. The data service supports using keywords in place of IRI strings as identifiers.

Such keywords must be declared in the configuration of the application:  resources/class-names.edn for class names, resources/property-names.edn for property names. By convention, keywords use a short, CURIE-like namespace (specified in resources/namespaces.edn), followed by the English label of the resource (defined in the underlying OWL file), in CamelCase for class names, and kebab-case for properties.

These files should be manually maintained. If changing the keyword for an IRI, be careful to update all references in the code. Adding to the keyword definitions is supported, but be careful not to accidentally rename an existing IRI. Automatic maintenance, via reference to the OWL file and automated code refactoring, is a possible future feature.

## Usage and examples

Rather than write a query that retrieves all the needed data upfront, it is intended that users specify an initial set of nodes to work with, then retrieve data from them using graph-traversal functions. This means that queries will be minimal compared to other systems, with significant portions of work farmed off to other functions.

### Single node with IRI

The [resource](#) function creates an RDFResource with the specified IRI. Objects created with this function in the context of the default datastore. For example:

    > (def intellectual-disability (resource "http://purl.obolibrary.org/obo/MONDO_0001071"))
    
Properties connected to this resource can be accessed using hash semantics

    (:rdfs/label intellectual-disability)
    ["intellectual disability"]
    
The ld-> and ld1-> functions can also be used to access properties

    (ld1-> intellectual-disability [:rdfs/label])
    
Note the use of a namespaced keyword, :rdfs/label in place of the full iri of the property "http://www.w3.org/2000/01/rdf-schema#label". Also note the fact that hash-access returns a vector, while the ld1-> function returns a singleton (the ld-> function would return a vector instead. Since the arity of RDF properties is not defined in a schema, these functions always assume that a collection can be returned, and will only return singleton objects if explicitly specified via the ld1-> function.

### Property direction

The above example demonstrates access of properties with the given resource as the subject (and not the object) of the property. For example:

    (:rdfs/sub-class-of intellectual-disability)
    [#object[clingen_search.database.query.RDFResource 0x69431749 "http://purl.obolibrary.org/obo/MONDO_0005503"]]
    
retrieves the superclasses of the given resource (intellectual disability is a subclass of developmental disorder of mental health [MONDO:0005503]). In order to retrieve the subclasses of intellectual disability, we must reverse the direction of the link:

    (get intellectual-disability [:rdfs/sub-class-of :<])
    [#object[clingen_search.database.query.RDFResource 0x1af5eb7f "http://purl.obolibrary.org/obo/MONDO_0042498"]
    #object[clingen_search.database.query.RDFResource 0x23b31af4 "http://purl.obolibrary.org/obo/MONDO_0022094"]
     #object[clingen_search.database.query.RDFResource 0x2f30ea5 "http://purl.obolibrary.org/obo/MONDO_0044322"]
     #object[clingen_search.database.query.RDFResource 0xbd72c1 "http://purl.obolibrary.org/obo/MONDO_0000508"]
     #object[clingen_search.database.query.RDFResource 0x6aae2634 "http://purl.obolibrary.org/obo/MONDO_0000509"]
     #object[clingen_search.database.query.RDFResource 0x51f1f52f "http://purl.obolibrary.org/obo/MONDO_0043099"]
     #object[clingen_search.database.query.RDFResource 0x4bed5f92 "http://purl.obolibrary.org/obo/MONDO_0010252"]
     #object[clingen_search.database.query.RDFResource 0x20ad1754 "http://purl.obolibrary.org/obo/MONDO_0043139"]
     #object[clingen_search.database.query.RDFResource 0x3b291af3 "http://purl.obolibrary.org/obo/MONDO_0021971"]
     #object[clingen_search.database.query.RDFResource 0x65afd834 "http://purl.obolibrary.org/obo/MONDO_0022020"]]
    
Instead of using the property name directly, we use a vector with the property name and a keyword signifying direction, :< is used to specify that the resource is the intended object of the property, :> is used to specify the resource is the intended subject of the property, :- is used to specify links going both directions.

### Property chains

Frequently, the properties one is looking to find are nested in the graph a few layers deep. The ld-> and ld1-> functions are convenience methods designed to make finding this data easier. They both accept as an argument a property list and return the set of results found after traversing the given properties. For example, the labels of the subclasses of Intellectual Disability:

```
(ld-> intellectual-disability [[:rdfs/sub-class-of :<] :rdfs/label])
("Ruzicka-Goerz-Anton syndrome"
 "cartwright Nelson Fryns syndrome"
 "intellectual developmental disorder with neuropsychiatric features"
 "syndromic intellectual disability"
 "non-syndromic intellectual disability"
 "hordnes engebretsen knudtson syndrome"
 "intellectual disability, X-linked, with panhypopituitarism"
 "microcephaly sparse hair mental retardation seizures"
 "Baraitser Rodeck garner syndrome"
 "boudhina yedes khiari syndrome")
```

### Selecting nodes with SPARQL

In addition to selecting nodes directly with an identifier, it is also possible to use a SPARQL query. The [select](#) function is intended for this purpose. It takes as an argument a string containing a SPARQL query, which is expected to return a list of nodes. Optionally, it takes an argument list and/or a model to query. One may embed [keyword identifiers](#) in the SPARQL string, but be sure to separate them from surrounding syntax with whitespace.

Finding the first three genes:

```
(select "select ?x where { ?x a :so/Gene } limit 3")
[#object[clingen_search.database.query.RDFResource 0x5602dea6 "https://www.ncbi.nlm.nih.gov/gene/55847"]
 #object[clingen_search.database.query.RDFResource 0x6812403b "https://www.ncbi.nlm.nih.gov/gene/106481385"]
 #object[clingen_search.database.query.RDFResource 0x6b9e7003 "https://www.ncbi.nlm.nih.gov/gene/8559"]
```

The same, but passing the type as an argument:

```
(select "select ?x where {?x a ?type} limit 3" {:type :so/Gene})
[#object[clingen_search.database.query.RDFResource 0x78111691 "https://www.ncbi.nlm.nih.gov/gene/55847"]
 #object[clingen_search.database.query.RDFResource 0x2aca5c9 "https://www.ncbi.nlm.nih.gov/gene/106481385"]
 #object[clingen_search.database.query.RDFResource 0x4f2d4e04 "https://www.ncbi.nlm.nih.gov/gene/8559"]]
```

The (optional) second argument to select accepts a map of parameters. Variables in the query (any term beginning with ?) that match a key in the map will be substituted for the associated value.

The third (optional) parameter accepts a Jena model, in case you want to run the query against a model other than the database for the application. The RDFResources returned from the query will be in the context of the passed-in model, rather than the entire database.
