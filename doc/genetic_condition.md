# Genetic Condition

## Requirements:

* Should genetic conditions transfer across curations?
  * If yes, we will need a system for managing genetic conditions.
  * If no, the requirements for querying the data to bring together related curations may become more complicated, but the requirements on implementation are lighter.
* Not necessarily an either-or; there may be a requirement to support both unregistered genetic conditions as well as a genetic condition registry.

## Solution w/o registry

* Identify when a genetic condition exsits that meets the requirements from the user. This could mean:
  * The user has specified a genetic condition target already for the condition (either an OMIM id with a gene target, or a previously specified GC.
* In the case that the user has specified a GC, return that identifier, leave message unmodified.
* In the case that the user did not directly specify a GC, restructure the message with one:
  * Make the target of the association a blank node:
  * subClassOf the previous condition
  * associated gene linked
  * Label is <condition>, <gene>
  
## Queries involving unregistered conditions
* Likely need a query to find genetic conditions beneath the containing condition.
  * Can be part of the GraphQL schema
* Need a type for genetic condition

    
## Minting a new genetic condition 




### Modifying existing message

### vs. Genetic Disease Registry

### vs. Regular Disease Registry

### IRI

Options are:

* Treat as blank node, no IRI
  * This is a problem if multiple curations need to reference the same disorder; blank nodes really only have significance within a single document, 

## Migrating genetic conditions to standard terms

* 
