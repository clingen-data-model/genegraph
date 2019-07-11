(defproject clingen-search "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.4.500"]
                 [io.pedestal/pedestal.service "0.5.5"]
                 [io.pedestal/pedestal.jetty "0.5.5"]
                 [hiccup "1.0.5"]
                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [org.apache.kafka/kafka-clients "2.3.0"]
                 [cheshire "5.8.1"]
                 [org.clojure/data.csv "0.1.4"]
                 [camel-snake-kebab "0.4.0"]
                 [org.apache.jena/jena-core "3.10.0"]
                 [org.apache.jena/jena-arq "3.10.0"]
                 [org.apache.jena/jena-iri "3.10.0"]
                 [org.apache.jena/jena-tdb2 "3.10.0"]
                 [org.apache.jena/jena-text "3.10.0" :exclusions [org.apache.jena/jena-cmds]]
                 [org.topbraid/shacl "1.1.0"]
                 [mount "0.1.14"]
                 [com.velisco/clj-ftp "0.3.9"] ;; For downloading ftp docs
                 [clj-http "3.9.1"] ;; For downloading 
                 [com.walmartlabs/lacinia-pedestal "0.12.0"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "clingen-search.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.5"]]}
             :uberjar {:aot [clingen-search.server]}}
  :main ^{:skip-aot true} clingen-search.server)

