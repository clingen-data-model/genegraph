(defproject clingen-search "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.pedestal/pedestal.service "0.5.5"]

                 ;; Remove this line and uncomment one of the next lines to
                 ;; use Immutant or Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.5.5"]
                 ;; [io.pedestal/pedestal.immutant "0.5.4"]
                 ;; [io.pedestal/pedestal.tomcat "0.5.4"]
                 [hiccup "1.0.5"]
                 ;; experimental--may remove in future
                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 ;; [org.neo4j/neo4j "3.4.9"]
                 ;; [org.neo4j/neo4j-bolt "3.4.9"]
                 ;; [org.neo4j/neo4j-kernel "3.4.9"]
                 ;; [org.neo4j/neo4j-io "3.4.9"]
                 [org.apache.kafka/kafka-clients "2.0.0"]
                 [cheshire "5.8.1"]
                 [camel-snake-kebab "0.4.0"]
                 [org.apache.jena/jena-core "3.10.0"]
                 [org.apache.jena/jena-arq "3.10.0"]
                 [org.apache.jena/jena-iri "3.10.0"]
                 [org.apache.jena/jena-tdb2 "3.10.0"]
                 [org.apache.jena/jena-text "3.10.0" :exclusions [org.apache.jena/jena-cmds]]
                 [mount "0.1.14"]
                 [com.velisco/clj-ftp "0.3.9"] ;; For downloading ftp docs
                 [clj-http "3.9.1"] ;; For downloading 
                 [org.apache.kafka/kafka-clients "2.1.1"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "clingen-search.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.5"]]}
             :uberjar {:aot [clingen-search.server]}}
  :main ^{:skip-aot true} clingen-search.server)

