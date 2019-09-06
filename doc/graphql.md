# GraphQL API

## Libraries

The data service relies on three libraries to provide a GraphQL API:

* **[Pedestal](http://pedestal.io)** HTTP serving and routing
* **[Lacinia](https://lacinia.readthedocs.io/en/latest/#)** GraphQL Server-side API
* **[Lacinia-pedestal](https://github.com/walmartlabs/lacinia-pedestal)** Exposes Lacinia GraphQL as Pedestal endpoints.

Lacinia is the only library that needs to be well-understood in order to extend the GraphQL APIs, although it's helpful to understand the 

