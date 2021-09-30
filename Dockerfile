FROM clojure:openjdk-11-tools-deps
LABEL maintainer="Tristan Nelson <thnelson@geisinger.edu>"

COPY deps.edn /usr/src/app/deps.edn
WORKDIR /usr/src/app
RUN clj -e :ok

COPY . /usr/src/app

EXPOSE 8888

CMD ["clj", "-X", "genegraph.server/run-server"]
