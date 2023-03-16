(ns genegraph.util
  (:import (java.io ByteArrayInputStream)))

(defn str->bytestream
  "If s is a string, return an input stream of its contents, else return s"
  [s]
  (if (string? s)
    (-> s .getBytes ByteArrayInputStream.)
    s))

(defn dissoc-ns
  "Dissocs keys from M which have the namespace qualifier NAMESPACE-KW"
  [m namespace-kw]
  (-> (keys m)
      (->> (remove #(= namespace-kw (-> % namespace keyword)))
           (select-keys m))))

(defn unchunk
  "Return a lazy seq over s that is not chunked."
  [s]
  (lazy-cat [(first s)] (unchunk (rest s))))

(defn coll-subtract
  "For each item in (seq B), remove an instance of this item in A, if it exists.
   If an item appears multiple times in A, remove as many as appear in B."
  [A B]
  (letfn [(flatten1 [things]
            (for [vec things elem vec] elem))]
    (let [freqA (frequencies A)
          freqB (frequencies B)
          freqAminusB (select-keys (merge-with - freqA freqB)
                                   (keys freqA))]
      (flatten1
       (for [[remA ct] (filter (fn [[v c]] (< 0 c)) freqAminusB)]
         (repeat ct remA))))))
