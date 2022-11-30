(ns genegraph.transform.clinvar.hgvs)

(def sequence-info-re #"(.+)?:(.+)?\.(.*)")

; pattern for everything after the c./g./etc
(def coord-ranges-re #"\([\d_\?]+_[\d_\?]+\)_\([\d_\?]+_[\d_\?]+\)")
(def coord-range-re #"\(([\d_\?]+)_([\d_\?]+)\)")

(defn maybe-parse-int
  "Parses s as a java.lang.Long, or returns s if it can't"
  [^String s]
  (or (parse-long s) s))

(defn do-to-one-or-more
  "If thing is a collection, map f to (seq thing), otherwise call f on thing."
  [thing f]
  (cond
    (coll? thing) (map f thing)
    :else (f thing)))

(defn hgvs-parse-sequence-and-location
  "Attempts to parse out HGVS terms from input-expression.
   {:accession The NC_... expression name
    :sequence-type g, c, m, etc
    :start Either an integer, a question mark, or a vector of two of either. 123, [123, ?], etc
    :end The same as :start
    :remainder Everything after the end location. A substitution, del, dup
    :input-expression The original expression passed in}"
  [input-expression]
  (letfn [(start-end-ints [{:keys [start end] :as %}]
            (-> %
                (assoc :start (do-to-one-or-more start maybe-parse-int))
                (assoc :end (do-to-one-or-more end maybe-parse-int))))
          (add-start [{:keys [remainder] :as %}]
            (if-let [start-range (re-find (re-pattern (str "^" coord-range-re "_" "(.+)")) remainder)]
              (assoc %
                     :start (-> start-range rest drop-last)
                     :remainder (last start-range))
              (assoc %
                     :start (nth (re-find #"(\d+)_" remainder) 1)
                     :remainder (nth (re-find #"(\d+)_(.*)" remainder) 2))))
          (add-end [{:keys [remainder] :as %}]
            (if-let [end-range (re-find (re-pattern (str coord-range-re "(.*)")) remainder)]
              (assoc %
                     :end (-> end-range rest drop-last)
                     :remainder (-> end-range last))
              (assoc %
                     :end (nth (re-find #"(\d+)" remainder) 1)
                     :remainder (nth (re-find #"(\d+)(.*)" remainder) 2))))]
    (let [[matching-substring
           accession
           sequence-type
           remainder]
          (re-find (re-pattern sequence-info-re) (or input-expression ""))]
      (some-> matching-substring
              (#(identity {:expression input-expression
                           :matching-substring %
                           :accession accession
                           :sequence-type sequence-type
                           :remainder remainder}))
              add-start
              add-end
              start-end-ints))))

(defn parsed-expression-span
  "Takes a map of expression terms returned by hgvs-parse-sequence-and-location.
   Returns the largest span length that the variant can be definitively said to span.
   For example if either endpoint has an indefinite outer bound, the length is calculated
   using that endpoint's inner bound. If either endpoint has no definite bound, the span is 0."
  [{:keys [start end] :as expr-map}]
  (let [min-start (some->> [start] flatten (filter int?) not-empty (apply min))
        max-end (some->> [end] flatten (filter int?) not-empty (apply max))]
    (if (and min-start max-end)
      (- max-end min-start) 0)))
