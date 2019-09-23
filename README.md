# ClinGen website data service

Current iteration of ClinGen website data service. Based on Clojure, Apache Jena, and Pedestal.

## Documentation

The Codox plugin is installed for this project. In order to build the documentation, type:

    lein codox
    
from the project directory. Resulting documentation is available in target/doc.

## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).

## REBL

To run REBL for Genegraph:

```
genegraph.server> (require 'cognitect.rebl)
nil
genegraph.server> (cognitect.rebl/ui)
nil
```


### [Docker](https://www.docker.com/) container support

1. Build an uberjar of your service: `lein uberjar`
2. Build a Docker image: `docker build -t clingen-search .`
3. Run your Docker image: `docker run -p 8888:8888 clingen-search`

## Links
* [Other examples](https://github.com/pedestal/samples)

