FROM java:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/clingen-search-0.0.1-SNAPSHOT-standalone.jar /clingen-search/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/clingen-search/app.jar"]
