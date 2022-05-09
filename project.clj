(defproject genegraph "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies
  [[org.clojure/clojure "1.11.1"]
   [org.clojure/core.async "1.5.648"]
   [org.clojure/data.csv "1.0.1"]
   [camel-snake-kebab "0.4.2"]
   [ch.qos.logback/logback-classic "1.2.11"]
   ;; Why not just clojure.data.json? =tbl
   [cheshire "5.10.2"]
   ;; fs included for delete-dir and mkdirs. =tbl
   [clj-commons/fs "1.6.310"]
   [clj-http "3.12.3"] ;; For downloading
   [com.apicatalog/titanium-json-ld "1.3.0"]
   [com.fasterxml.jackson.core/jackson-core "2.13.2"]
   [com.fasterxml.jackson.core/jackson-databind "2.13.2"]
   [com.github.danielwegener/logback-kafka-appender "0.2.0-RC2"]
   [com.google.cloud/google-cloud-storage "2.6.0"]
   ;; :exclusions works around LICENSE/license filename case conflict in build.
   [com.google.firebase/firebase-admin "8.1.0"
    :exclusions [io.grpc/grpc-netty-shaded]]
   [com.taoensso/nippy "3.1.1"]
   [com.velisco/clj-ftp "0.3.17"] ;; For downloading ftp docs
   [com.walmartlabs/lacinia-pedestal "1.0"]
   [digest "1.4.10"]
   [io.maryk.rocksdb/rocksdbjni "6.25.3"]
   [io.pedestal/pedestal.interceptor "0.5.10"]
   [io.pedestal/pedestal.jetty "0.5.10"]
   [io.pedestal/pedestal.service "0.5.10"]
   ;; Dirwatch for updating base files in development
   [juxt/dirwatch "0.2.5"]
   [lein-cljfmt "0.8.0"]
   ;; medley included for filter-keys and deep-merge. =tbl
   [medley "1.4.0"]
   [mount "0.1.16"]
   [org.apache.jena/jena-arq "3.17.0"]
   [org.apache.jena/jena-core "3.17.0"]
   [org.apache.jena/jena-iri "3.17.0"]
   [org.apache.jena/jena-tdb2 "3.17.0"]
   [org.apache.jena/jena-text "3.17.0"]
   [org.apache.kafka/kafka-clients "3.1.0"]
   ;; Jena 3.17.0 includes codecs from Lucene 7.7.3. =tbl
   [org.apache.lucene/lucene-suggest "7.7.3"]
   [org.codehaus.janino/janino "3.1.6"]
   ;; ordered included for generating jsonld context with ordered-map. =tbl
   [org.flatland/ordered "1.15.10"]
   [org.glassfish/jakarta.json "2.0.1"]
   [org.slf4j/jcl-over-slf4j "1.7.36"]
   [org.slf4j/jul-to-slf4j "1.7.36"]
   [org.slf4j/log4j-over-slf4j "1.7.36"]
   ;; dependency superceeded by antlr provided via lacinia dependency
   [org.topbraid/shacl "1.4.2"]
   ;; REBL reqs
   [org.openjfx/javafx-base     "16"]
   [org.openjfx/javafx-controls "16"]
   [org.openjfx/javafx-fxml     "16"]
   [org.openjfx/javafx-swing    "16"]
   [org.openjfx/javafx-web      "16"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources", "jars/rebl-0.9.242.jar"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct
  ;; alpn-boot dependency
  ;; :java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles
  {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "genegraph.server/run-dev"]}
         :dependencies [[io.pedestal/pedestal.service-tools "0.5.5"]]}
   :uberjar {:aot [genegraph.server]
             :uberjar-name "app.jar"}}
  :repl-options {:caught clojure.repl/pst}
  :plugins [[lein-codox "0.10.7"]]
  :codox {:output-path "docs"}
  :jvm-opts ["-XX:MaxRAMPercentage=50"]
  :main ^{:skip-aot true} genegraph.server)
