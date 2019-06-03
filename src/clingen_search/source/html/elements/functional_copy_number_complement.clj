(ns clingen-search.source.html.elements.functional-copy-number-complement
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))


;;(first (:geno/has-count copy-number))
(defmethod e/column :geno/FunctionalCopyNumberComplement [copy-number]
  [:div.column (e/link copy-number)])

(defmethod e/title :geno/FunctionalCopyNumberComplement [copy-number]
  [:h1.title (e/link (first (:geno/is-feature-affected-by copy-number)))
   " x" (first (:geno/has-member-count copy-number))])

(defmethod e/link :geno/FunctionalCopyNumberComplement [copy-number]
  (if-let [prop (q/ld1-> copy-number [[:sepio/has-subject :<]])]
    [:a {:href (q/path prop)}
     "copy number " (first (:geno/has-member-count copy-number))]))

