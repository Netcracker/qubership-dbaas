[![Maven build](https://github.com/Netcracker/qubership-dbaas/actions/workflows/maven-build.yaml/badge.svg)](https://github.com/Netcracker/qubership-dbaas/actions/workflows/maven-build.yaml)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?metric=coverage&project=Netcracker_qubership-dbaas)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-dbaas)
[![duplicated_lines_density](https://sonarcloud.io/api/project_badges/measure?metric=duplicated_lines_density&project=Netcracker_qubership-dbaas)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-dbaas)
[![vulnerabilities](https://sonarcloud.io/api/project_badges/measure?metric=vulnerabilities&project=Netcracker_qubership-dbaas)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-dbaas)
[![bugs](https://sonarcloud.io/api/project_badges/measure?metric=bugs&project=Netcracker_qubership-dbaas)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-dbaas)
[![code_smells](https://sonarcloud.io/api/project_badges/measure?metric=code_smells&project=Netcracker_qubership-dbaas)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-dbaas)

# DBaaS Aggregator API



## Overview
This documentation presents the REST API for the “Database as a Service” (DBaaS) component. DBaaS acts as an aggregator for all adapters. It is designed to collect requests for managed databases and route them to the appropriate adapter. DBaaS stores information about all databases used in a cloud project. These databases are isolated by namespace. DBaaS uses a Classifier to identify databases within a cloud namespace. The Classifier includes service-related information such as scope, microservice name, tenant ID, and namespace.

* Installation notes: [installation note.md](./docs/installation/installation.md)
* List of supported APIs: [rest-api docs](./docs/rest-api.md)
* Information about DBaaS features: https://perch.qubership.org/display/CLOUDCORE/DbaaS+Features 
