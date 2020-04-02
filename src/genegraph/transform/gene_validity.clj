(ns genegraph.transform.gene-validity
  (:require [genegraph.database.load :as l]
            [genegraph.database.query :as q :refer [select construct ld-> ld1-> declare-query]]
            [genegraph.transform.core :refer [transform-doc src-path]]
            [cheshire.core :as json]
            [clojure.string :as s]
            [clojure.java.io :refer [resource]])
  (:import java.io.ByteArrayInputStream ))

(def base "http://dataexchange.clinicalgenome.org/gci/")

(declare-query construct-proposition
               five-genes)

;; Trim trailing }, intended to be appended to gci json
(def context
  (s/join ""
        (drop-last
         (json/generate-string
          {"@context" 
           {"@vocab" "http://gci.clinicalgenome.org/"
            "@base" "http://gci.clinicalgenome.org/"
            "gci" "http://gci.clinicalgenome.org/"
            "MONDO" "http://purl.obolibrary.org/obo/MONDO_"
            "hgncId" {"@type" "@id"}
            "diseaseId" {"@type" "@id"}
            }}))))

(defn parse-gdm [gdm-json]
  (let [gdm-with-fixed-curies (s/replace gdm-json #"MONDO_" "MONDO:")
        is (-> (str context "," (subs gdm-with-fixed-curies 1))
               .getBytes
               ByteArrayInputStream.)]
    (l/read-rdf is {:format :json-ld})))


;; TODO, this is incomplete, need more data on this
;; ask at next data model meeting
(def case-info-type-to-sepio-type
  {"PREDICTED_OR_PROVEN_NULL_VARIANT" :sepio/fixme
   "OTHER_VARIANT_TYPE_WITH_GENE_IMPACT" :sepio/fixme })


