(ns genegraph.source.graphql.common.enum
  (:require [genegraph.database.query :refer [resource]]))

;; TODO  BROKEN--can't create resources prior to db init, need another way
(def mode-of-inheritance
  {(resource :hpo/AutosomalDominantInheritance) :AUTOSOMAL_DOMINANT
   (resource :hpo/AutosomalRecessiveInheritance) :AUTOSOMAL_RECESSIVE
   (resource :hpo/XLinkedInheritance) :X_LINKED
   (resource :hpo/SemidominantModeOfInheritance) :SEMIDOMINANT
   (resource :hpo/MitochondrialInheritance) :MITOCHONDRIAL
   (resource :hpo/ModeOfInheritance) :UNDETERMINED})
