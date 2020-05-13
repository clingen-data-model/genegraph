(ns genegraph.source.graphql.common.enum
  (:require [genegraph.database.query :refer [resource]]))

(def mode-of-inheritance
  {:hpo/AutosomalDominantInheritance :AUTOSOMAL_DOMINANT
   :hpo/AutosomalRecessiveInheritance :AUTOSOMAL_RECESSIVE
   :hpo/XLinkedInheritance :X_LINKED
   :hpo/SemidominantModeOfInheritance :SEMIDOMINANT
   :hpo/MitochondrialInheritance :MITOCHONDRIAL
   :hpo/ModeOfInheritance :UNDETERMINED})
