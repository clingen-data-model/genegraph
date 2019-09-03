# Reading and Storing Data
(clingen-search.database.load)

The data service has a simple interface for storing data in the database. Data may be read into a Jena model [read-rdf](#), and stored into the database with [load-model](#). Data stored in the database must have an associated named graph, any graph with that name currently in the database will be replaced with the new data. Replacing a named graph in entierty using this API is currently the only recommended mechanism for updating the database. It is possible to access the underlying models for the database and update them using Jena APIs, but this is not recommended.

## Reading RDF

Most commonly, one will want to take incoming RDF in a serialized form and store it in the database. RDF/XML, JSON-LD, and Turtle are the formats currently supported (because they're the only formats listed in clingen-search.database.load/jena-rdf-format), but  any of the serializations supported by Jena can be added. 

The transform-doc method in clingen-search.transform.core has an example of this:

```
(defmethod transform-doc :rdf 
  ([doc-def] 
   (with-open [is (io/input-stream (str target-base (:target doc-def)))] 
     (l/read-rdf is (:reader-opts doc-def))))
  ([doc-def doc]
   (let [is (-> doc .getBytes java.io.ByteArrayInputStream.)]
     (l/read-rdf is (:reader-opts doc-def)))))
```

Note that when a string is passed to this function, it is interpreted as a URL to an RDF document. In order to read a string containing an RDF serialization (such as the values read from the streaming service), it needs to be transformed into an input stream.

## Storing RDF

Once a document has been read into a model, it can be stored in the database. The load-model function is used for this. It accepts a model and a graph name, both are required. Any existing graph with that name in the database will be replaced.

## Constructing RDF

When reading documents not already serialized in RDF it's necessary to tranform them first into a Jena model. A simple mechanism is provided for this via the [statements-to-model](#) function. This model accepts a sequence of subject-predicate-object triples and constructs them into a Jena model. The gene-as-triple function from clingen-search.transform.gene illustrates the features of this:

```
(defn gene-as-triple [gene]
  (let [uri (str "https://www.ncbi.nlm.nih.gov/gene/" (:entrez_id gene))
        hgnc-id (:hgnc_id gene)
        ensembl-iri (str "http://rdf.ebi.ac.uk/resource/ensembl/"
                                    (:ensembl_gene_id gene))]
    (concat [[uri :skos/preferred-label (:symbol gene)]
             [uri :skos/alternative-label (:name gene)]
             ^{:object :Resource} [uri :owl/same-as hgnc-id]
             [hgnc-id :dc/source (resource hgnc)]
             ^{:object :Resource} [uri :owl/same-as ensembl-iri]
             [ensembl-iri :dc/source (resource ensembl)]
             [uri :rdf/type :so/Gene]]
            (map #(vector uri :skos/hidden-label %)
                 (:alias_symbol gene))
            (map #(vector uri :skos/hidden-label %)
                 (:prev_name gene)))))
```

* The subject may be a keyword, string, RDFResource, or Jena Resource. Strings in the subject are interpreted as the IRI of a resource. Keywords are translated into resources via the [lookup table](#).
* The predicate may be a keyword listed in the lookup table or a string. Strings are translated into predicate IRIs
* The object may be a keyword, string, RDFResrouce or Jena Resource. Strings in the object are treated as string literals, unless the metadata ^{:object :Resource} is set on the triple.

Once constructed via statements-to-model, constructed models may be stored in the database using load-model.
