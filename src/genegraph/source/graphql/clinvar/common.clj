(ns genegraph.source.graphql.clinvar.common
  (:require [genegraph.database.names :refer [prefix-ns-map]]))

(defn cgterm
  [property-name]
  (str (prefix-ns-map "cgterms") property-name))


(defn parse-curie
  "Parses a CURIE formatted string into a prefix and value.
  If the string contains multiple colons (:), the prefix is taken to be the first
  non-empty substring before a colon."
  [^String text]
  (if text
    (re-matches #"(^[\S]+):(\S+)" text)))

(defn is-curie
  "Returns true if `parse-curie` returns non-nil (matches pattern)."
  [text]
  (not (nil? (parse-curie text))))

(defn is-prefix-known [^String prefix]
  (contains? prefix-ns-map prefix))

(defn prefix-to-ns [^String prefix]
  (prefix-ns-map prefix))

(defn resolve-curie-namespace
  "Helper function to parse incoming CURIE strings.
  If it looks like a CURIE and the prefix is known in namespaces.edn, returns the expansion
  of the namespace concatenated with the identifier."
  [text]
  (or (let [[_ prefix id] (parse-curie text)]
        (if (is-prefix-known prefix)
          (str (prefix-to-ns prefix) id)))
      text))
