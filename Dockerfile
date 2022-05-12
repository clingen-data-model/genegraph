FROM clojure:temurin-17-tools-deps-alpine AS builder

# Copying and building deps as a separate step in order to mitigate
# the need to download new dependencies every build.
COPY deps.edn /usr/src/app/deps.edn
WORKDIR /usr/src/app
RUN clojure -P
COPY . /usr/src/app
RUN clojure -T:build uber

# Using image without lein for deployment.
FROM eclipse-temurin:17-alpine
LABEL maintainer="Tristan Nelson <thnelson@geisinger.edu>"

# libstdc++ needed for rocksdbjni, not otherwise present in alpine
RUN apk add --no-cache libstdc++~=10

COPY --from=builder /usr/src/app/target/genegraph.jar /app/app.jar

EXPOSE 8888

CMD ["java", "-jar", "/app/app.jar"]