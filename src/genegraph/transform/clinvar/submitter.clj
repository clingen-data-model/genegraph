(ns genegraph.transform.clinvar.submitter
  (:require [genegraph.rocksdb :as rocksdb]
            [genegraph.transform.clinvar.iri :refer [ns-cg]]
            [mount.core :as mount]
            [genegraph.transform.clinvar.common :as common]))

#_(-> "/Users/kferrite/dev/clinvar-streams/clinvar-raw-2023-02-08_submitter.txt"
      io/reader
      line-seq
      first
      prn)
'"{\"release_date\":\"2022-02-08\",\"event_type\":\"create\",\"content\":{\"current_name\":\"KK Women’s and Children’s Hospital\",\"entity_type\":\"submitter\",\"all_names\":[\"KK Women’s and Children’s Hospital\"],\"org_category\":\"other\",\"content\":\"null\",\"clingen_version\":0,\"all_abbrevs\":[\"KKH\"],\"id\":\"506077\",\"current_abbrev\":\"KKH\"}}"

(mount/defstate submitter-data-db
  :start (rocksdb/open "submitter-snapshot.db")
  :stop (rocksdb/close submitter-data-db))

;; https://github.com/ga4gh/va-spec/blob/4fd8a1a07f274b6d8e19f7c69d0de0d912282e3b/schema/annotation.json#L47
(defn add-data-for-submitter [event]
  (let [message (:genegraph.transform.clinvar.core/parsed-value event)
        submitter (:content message)
        release-date (:release_date message)
        vof (str (ns-cg "clinvar_submitter_") (:id submitter))
        id (str vof ".")
        data {:id id
              :type "Agent"
              :label (-> submitter :current_name)
              ;; TODO subtype. organization concept from some ontology.
              ;; May want to look at org_category here and see what options there are
              :extensions (common/fields-to-extension-maps
                           (assoc (select-keys submitter [:alternate_names
                                                          :org_categority
                                                          :current_abbrev])
                                  :clinvar_submitter_id (:id submitter)))}]
    (assoc event
           :genegraph.annotate/data data
           :genegraph.annotate/data-db submitter-data-db
           :genegraph.annotate/iri id
           :genegraph.annotate/data-id id)))
