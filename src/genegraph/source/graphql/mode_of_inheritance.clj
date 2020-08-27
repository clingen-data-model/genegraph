(ns genegraph.source.graphql.mode-of-inheritance
  (:require [genegraph.source.graphql.common.cache :refer [defresolver]]
            [genegraph.database.query :as q]))

(def modes-of-inheritance-query 
  (q/create-query 
   "select distinct ?moi where 
{ ?prop a :sepio/GeneValidityProposition .
  ?prop :sepio/has-qualifier ?moi }"))

(defresolver modes-of-inheritance [args value]
  (modes-of-inheritance-query))
