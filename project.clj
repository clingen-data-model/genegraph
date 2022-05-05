(defproject genegraph "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.3.610" :exclusions [org.clojure/tools.reader]]
                 [io.pedestal/pedestal.service "0.5.8"]
                 [io.pedestal/pedestal.jetty "0.5.8"]
                 [io.pedestal/pedestal.interceptor "0.5.8"]
                 [hiccup "1.0.5"]
                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.26"]
                 [org.slf4j/jcl-over-slf4j "1.7.26"]
                 [org.slf4j/log4j-over-slf4j "1.7.26"]
                 [com.github.danielwegener/logback-kafka-appender "0.2.0-RC2"]
                 [org.apache.kafka/kafka-clients "3.1.0"]
                 [cheshire "5.8.1"]
                 [org.clojure/data.csv "0.1.4"]
                 [camel-snake-kebab "0.4.0"]
                 [org.apache.jena/jena-text "3.17.0" :exclusions [org.apache.jena/jena-cmds]]
                 [org.apache.jena/jena-tdb2 "3.17.0"]
                 [org.apache.jena/jena-arq "3.17.0"]
                 [org.apache.jena/jena-core "3.17.0"]
                 [org.apache.jena/jena-iri "3.17.0"]
                 [org.apache.lucene/lucene-suggest "7.7.3"] ;; jena 3.14.0 includes lucene 7.4.0
                 [commons-codec/commons-codec "1.15"] ;; Req by Jena--not in deps
                 ;; Dependency superceeded by antlr provided via lacinia dependency
                 [org.topbraid/shacl "1.3.2" :exclusions [org.antlr/antlr4-runtime]]
                 [mount "0.1.16"]
                 [com.velisco/clj-ftp "0.3.9"] ;; For downloading ftp docs
                 [clj-http "3.9.1"] ;; For downloading
                 [com.walmartlabs/lacinia-pedestal "0.15.0"]
                 [clj-commons/fs "1.5.2"]
                 ;; Dirwatch for updating base files in development
                 [juxt/dirwatch "0.2.5"]
                 ;; for generating jsonld context
                 [org.flatland/ordered "1.5.7"]
                 [com.apicatalog/titanium-json-ld "1.1.0"]
                 [org.glassfish/jakarta.json "2.0.1"]
                 ;; REBL reqs
                 [lein-cljfmt "0.6.4"]
                 [org.openjfx/javafx-fxml "16"]
                 [org.openjfx/javafx-controls "16"]
                 [org.openjfx/javafx-swing "16"]
                 [org.openjfx/javafx-base "16"]
                 [org.openjfx/javafx-web "16"]
                 [expound "0.7.2"]
                 [medley "1.3.0"]
                 [buddy/buddy-sign "3.2.0"]
                 [com.fasterxml.jackson.core/jackson-core "2.11.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.11.0"]
                 [com.google.cloud/google-cloud-storage "1.108.0"]
                 [org.rocksdb/rocksdbjni "6.11.4"]
                 [com.taoensso/nippy "3.1.1"]
                 [digest "1.4.9"]
                 [com.google.firebase/firebase-admin "7.0.1"]
                 [org.codehaus.janino/janino "3.1.3"]
                 [nrepl "0.8.3"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources", "jars/rebl-0.9.242.jar"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "genegraph.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.5"]]}
             :uberjar {:aot [genegraph.server]
                       :uberjar-name "app.jar"}
             :uberjar-repl {:uberjar-name "app.jar"
                            :aot [genegraph.server-repl]
                            :main genegraph.server-repl}}
  :repl-options {:caught clojure.repl/pst}
  :plugins [[lein-codox "0.10.7"]]
  :codox {:output-path "docs"}
  :jvm-opts ["-XX:MaxRAMPercentage=50"]
  :main ^{:skip-aot true} genegraph.server)

