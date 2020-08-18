(ns genegraph.database.query.algebra
    (:require [genegraph.database.instance :refer [db]]
            [genegraph.database.util :as util :refer [tx]]
            [genegraph.database.names :as names :refer
             [local-class-names local-property-names
              class-uri->keyword local-names ns-prefix-map
              prefix-ns-map property-uri->keyword]]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.core.protocols :as protocols :refer [Datafiable]]
            [clojure.datafy :as datafy :refer [datafy nav]]
            [io.pedestal.log :as log]
            [medley.core :as medley]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy])
    (:import [org.apache.jena.rdf.model Model Statement ResourceFactory Resource Literal RDFList SimpleSelector ModelFactory]
             [org.apache.jena.query Dataset QueryFactory Query QueryExecution
              QueryExecutionFactory QuerySolutionMap]
             [org.apache.jena.sparql.algebra AlgebraGenerator Algebra OpAsQuery Op]
             [org.apache.jena.graph Node NodeFactory Triple Node_Variable Node_Blank]
             [org.apache.jena.sparql.algebra.op OpDistinct OpProject OpFilter OpBGP OpConditional OpDatasetNames OpDiff OpDisjunction OpDistinctReduced OpExtend OpGraph OpGroup OpJoin OpLabel OpLeftJoin OpList OpMinus OpNull OpOrder OpQuad OpQuadBlock OpQuadPattern OpReduced OpSequence OpSlice OpTopN OpUnion OpTable ]
             [org.apache.jena.sparql.core BasicPattern Var VarExprList QuadPattern Quad]
             org.apache.jena.riot.writer.JsonLDWriter
             org.apache.jena.sparql.core.Prologue
             org.apache.jena.riot.RDFFormat$JSONLDVariant
             java.io.ByteArrayOutputStream))

