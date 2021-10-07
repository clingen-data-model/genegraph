# Changelog

## 2021-10-05

Changed migration to include build image and date in migration name
for reproducibility of deployments. 

Cloudbuild includes changes to update architecture project related
artifacts for Argo CD integration purposes.

## 2021-07-08

Add parameter to gene_curations for affiliation to filter by the role the affiliate played in the curation

## 2021-06-30

Fix startup race condition in genegraph between stream event processing and resolver cache warming

## 2021-06-14

Incorporate secondary approvers and contributions from GCI data

Add field to GraphQL to represent all contributions for gene validity assertion

Include GCEP and VCEP as well as affiliations

## 2021-06-02

Updated to incorporate two new actionability assertion types based on rule-outs
