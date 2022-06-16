(ns genegraph.annotate.cnv_test
  "Test copy number variation parsing."
  (:require [clojure.spec.alpha :as s]
            [genegraph.annotate.cnv :as cnv]))

(def examples
  "Examples of CNV string syntax."
  ["GRCh37/hg19 1q21.1(chr1:143134063-143284670)x3"
   "GRCh38/hg38 1p36.33(chr1:1029317-1072906)x4"
   "GRCh37/hg19 Yp11.32(chrY:21267-39498)x0"
   "NCBI36/hg18 Xq21.31(chrX:88399122-88520760)x1"
   "GRCh37/hg19 Xp22.33(chrX:697169-1238257)x0"
   "GRCh37/hg19 13q31.3(chr13:93244802-93269486)x0"
   "GRCh38/hg38 6q16.1-16.2(chr6:98770647-99813111)x1"])

(assert (= examples (mapv (comp cnv/unparse cnv/parse) examples)))

(s/valid? ::cnv/cnv
          {::cnv/cytogenetic-location "1q21.1"
           ::cnv/reference "hg19"
           ::cnv/string "GRCh37/hg19 1q21.1(chr1:143134063-143284670)x3"
           ::cnv/variation-id 145208
           :assembly "GRCh37"
           :chr "1"
           :end 143284670
           :start 143134063
           :total_copies 3})
(s/valid? ::cnv/cnv nil)
