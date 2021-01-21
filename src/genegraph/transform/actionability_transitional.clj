(ns genegraph.transform.actionability-transitional
  "Building the rough structure of actionability assertions
  based on the existing JSON transfered from the ACI via the
  Data Exchange"
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q]
            [genegraph.transform.types :refer [transform-doc src-path add-model]]
            [cheshire.core :as json]
            [clojure.string :as s])
  (:import java.io.ByteArrayInputStream ))

(q/declare-query construct-actionability-report)

(def context
  (s/join ""
        (drop-last
         (json/generate-string
          {"@context" 
           {
            ;; frontmatter
            ;; "@vocab" "http://actionability.clinicalgenome.org/"
            "@base" "http://actionability.clinicalgenome.org/"
            "aci" "http://actionability.clinicalgenome.org/"
            "acixform" "http://dataexchange.clinicalgenome.org/acixform/"
            "type" "@type"
            "iri" {"@id" "http://dataexchange.clinicalgenome.org/acixform/iri"
                   "@type" "@id"}
            "gene" {"@id" "http://dataexchange.clinicalgenome.org/acixform/gene"
                    "@type" "@id"}
            "assertions" {"@id" "http://dataexchange.clinicalgenome.org/acixform/assertions"}
            "assertion" {"@id" "http://dataexchange.clinicalgenome.org/acixform/assertion"
                         "@type" "@vocab"}
            "conditions" {"@id" "http://dataexchange.clinicalgenome.org/acixform/conditions"}
            "dateISO8601" {"@id" "http://dataexchange.clinicalgenome.org/acixform/approvalDate"}
            "searchDates" {"@id" "http://dataexchange.clinicalgenome.org/acixform/searchDates"}
            "affiliations" {"@id" "http://dataexchange.clinicalgenome.org/acixform/affiliations"}
            "id" {"@type" "@vocab"
                  "@id" "http://dataexchange.clinicalgenome.org/acixform/id"}

            "Definitive Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003535"
            "Strong Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003536"
            "Moderate Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003537"
            "Limited Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003538"
            "Insufficient Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003539"
            "No Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003540"
            "Assertion Pending" "http://purl.obolibrary.org/obo/SEPIO_0003541"
            
            "Pediatric AWG" "http://dataexchange.clinicalgenome.org/terms/PediatricActionabilityWorkingGroup"
            "Adult AWG" "http://dataexchange.clinicalgenome.org/terms/AdultActionabilityWorkingGroup"
            ;;:sepio/ActionabilityReport
            ;; "actionability" "http://purl.obolibrary.org/obo/SEPIO_0003010" 
            ;; common prefixes
            "MONDO" "http://purl.obolibrary.org/obo/MONDO_"
            "SEPIO" "http://purl.obolibrary.org/obo/SEPIO_"}}))))

(defn as-model [aci-json]
  (with-open [is (-> (str context "," (subs aci-json 1))
                     .getBytes
                     ByteArrayInputStream.)]
    (l/read-rdf is {:format :json-ld})))

(defn as-sepio [aci-model]
  (construct-actionability-report {::q/model aci-model}))
