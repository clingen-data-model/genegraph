(ns genegraph.transform.clinvar.cnv
  (:require [genegraph.annotate.cnv :as cnv]
            [genegraph.transform.clinvar.cancervariants :as vicc]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn load-messages-from-file []
  (-> "../clinvar-streams/clinvar-raw-testdata_20220523"
      io/reader
      line-seq
      (->> (map #(json/parse-string % true)))))

(defn -main [& _]
  (let [variation-messages (load-messages-from-file)]
    (->> (load-messages-from-file)
         (filter #(= "variation" (-> % :content :entity_type)))
         (filter #(.startsWith (-> % :content :variation_type) "copy number"))
         (map (fn [m] {:message m :cnv (cnv/parse (-> m :content :name))}))
         (map (fn [m] (assoc m :normalized
                             (vicc/normalize-absolute-copy-number (:cnv m))))))))
