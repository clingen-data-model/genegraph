(ns genegraph.source.snapshot.variation-descriptor
  (:require [genegraph.database.query :as q]
            [io.pedestal.log :as log]
            [genegraph.source.graphql.experimental-schema :as experimental-schema]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(def sparql-prefixes "
PREFIX dc: <http://purl.org/dc/terms/>
PREFIX cgterms: <http://dataexchange.clinicalgenome.org/terms/>
PREFIX sepio: <http://purl.obolibrary.org/obo/SEPIO_>
PREFIX vrs: <https://vrs.ga4gh.org/terms/>
PREFIX scv: <https://identifiers.org/clinvar.submission:>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>")

(def variation-descriptors-as-of-date-sparql "
SELECT ?iri #?vof ?version ?replaced_by ?replacing_version
WHERE {
  ?iri a vrs:CategoricalVariationDescriptor ;
       dc:isVersionOf ?vof ;
       owl:versionInfo ?version .
  FILTER(?version <= ?until_version)
  # Remove those replaced by another record also prior to ?until_version
  FILTER NOT EXISTS {
    ?iri dc:isReplacedBy ?replaced_by .
    ?replaced_by owl:versionInfo ?replacing_version .
    FILTER(?replacing_version <= ?until_version)
  }
  # Add additional returned variables if they exist
  OPTIONAL {
    ?iri dc:isReplacedBy ?replaced_by .
    ?replaced_by owl:versionInfo ?replacing_version .
  }
}
ORDER BY ASC(?vof) ASC(?version)
")

(defn offset-limit-lazyseq
  "Takes a query-def and params such as those provided to q/select
  Performs all reads in a transaction tx"
  [query params]
  (let [limit-cap 10
        provided-limit (-> params ::q/params (get :limit limit-cap))
        starting-offset (-> params ::q/params (get :offset 0))
        limit (min provided-limit limit-cap)]
    (map (fn []))))

;(defn- time-str-offset-to-instant [s]
;  ;; "2018-03-27T09:55:41.000-0400"
;  (->> s
;       -format-jira-datetime-string
;       OffsetDateTime/parse
;       Instant/from
;       str))

(def variation-descriptor-graphql-query
  "
query($variation_iri:String) {
  variation_descriptor_query(variation_iri: $variation_iri) {
    id: iri
    type: __typename
    ... on CategoricalVariationDescriptor {
      label
      value: object {
        id: iri
        type: __typename
        ... on CanonicalVariation {
          complement
          variation {
            ... alleleFields
          }
        }
        ... on Allele {
          ...alleleFields
        }
      }
      xrefs
      members {
        id: iri
        type: __typename
        expressions {
          type: __typename
          value
          syntax
          syntax_version
        }
      }
      extensions {
        type: __typename
        name
        value
      }
    }
  }
}

fragment alleleFields on Allele {
  location {
    id: iri
    type: __typename
    interval {
      type: __typename
      start {
        type: __typename
        value
      }
      end {
        type: __typename
        value
      }
    }
  }
}
")

(defn variation-descriptors-as-of-date
  [{:keys [until]}]
  (let [
        max-date-string "9999-99-99"
        date-parser (fn [date-string] ())
        ]
    (let [date-pattern #"\d{4}-\d{2}-\d{2}"]
      (assert (re-matches date-pattern max-date-string)
              (format "date string %s did not match %s pattern" max-date-string date-pattern)))
    (log/info :fn ::variation-descriptor :until until)
    ; Get descriptors as of a date which are not replaced
    (let [descriptor-resources (q/select (str sparql-prefixes "\n" variation-descriptors-as-of-date-sparql)
                                         {:until_version (or (str until) max-date-string)})]
      (for [descriptor-resource descriptor-resources]
        (do (println (str descriptor-resource))
            (let [gql-response (experimental-schema/query variation-descriptor-graphql-query {:variation_iri (str descriptor-resource)})]
              (log/info :gql-response gql-response)
              gql-response))))))

(defn write-variation-descriptors
  "Takes a seq of variation_descriptor_query response objects, writes them to a file"
  [response-objects file-name]
  (with-open [file (io/writer (io/file file-name))]
    (doseq [obj response-objects]
      (let [output-obj (get-in obj [:data :variation_descriptor_query])]
        (.write file (json/generate-string output-obj))
        (.write file "\n")))))
