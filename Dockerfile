# FROM java:8-alpine
FROM adoptopenjdk/openjdk12:latest
MAINTAINER Tristan Nelson <thnelson@geisinger.edu>

COPY keys/dev.serveur.keystore.jks /keys/dev.serveur.keystore.jks

COPY target/clingen-search-0.0.1-SNAPSHOT-standalone.jar /clingen-search/app.jar



EXPOSE 8888

CMD ["java", "-jar", "/clingen-search/app.jar"]
