(defproject genegraph "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.2.603"  :exclusions [org.clojure/tools.reader]]
                 [io.pedestal/pedestal.service "0.5.8"]
                 [io.pedestal/pedestal.jetty "0.5.8"]
                 [hiccup "1.0.5"]
                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.26"]
                 [org.slf4j/jcl-over-slf4j "1.7.26"]
                 [org.slf4j/log4j-over-slf4j "1.7.26"]
                 [org.apache.kafka/kafka-clients "2.5.0"]
                 [cheshire "5.8.1"]
                 [org.clojure/data.csv "0.1.4"]
                 [camel-snake-kebab "0.4.0"]
                 [org.apache.jena/jena-text "3.14.0" :exclusions [org.apache.jena/jena-cmds]]
                 [org.apache.jena/jena-tdb2 "3.14.0"]
                 [org.apache.jena/jena-arq "3.14.0"]
                 [org.apache.jena/jena-core "3.14.0"]
                 [org.apache.jena/jena-iri "3.14.0"]
                 [org.apache.lucene/lucene-suggest "7.4.0"] ;; jena 3.14.0 includes lucene 7.4.0
                 [org.topbraid/shacl "1.1.0"]
                 [mount "0.1.16"]
                 [com.velisco/clj-ftp "0.3.9"] ;; For downloading ftp docs
                 [clj-http "3.9.1"] ;; For downloading 
                 [com.walmartlabs/lacinia-pedestal "0.13.0"]
                 [clj-commons/fs "1.5.2"]
                 ;; Dirwatch for updating base files in development
                 [juxt/dirwatch "0.2.5"]
                 ;; for generating jsonld context
                 [org.flatland/ordered "1.5.7"]
                 ;; REBL reqs
                 [lein-cljfmt "0.6.4"]
                 [org.openjfx/javafx-fxml "11.0.1"]
                 [org.openjfx/javafx-controls "11.0.1"]
                 [org.openjfx/javafx-swing "11.0.1"]
                 [org.openjfx/javafx-base "11.0.1"]
                 [org.openjfx/javafx-web "11.0.1"]
                 [expound "0.7.2"]
                 [com.fasterxml.jackson.core/jackson-core "2.11.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.11.0"]
                 [com.google.cloud/google-cloud-storage "1.108.0"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources", "jars/REBL-0.9.220.jar"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "genegraph.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.5"]]}
             :uberjar {:aot [genegraph.server]
                       :uberjar-name "app.jar"}}
  :plugins [[lein-codox "0.10.7"]]
  :codox {:output-path "docs"}
  :main ^{:skip-aot true} genegraph.server)

