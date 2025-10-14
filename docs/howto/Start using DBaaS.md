# Overview

There are several approaches to using an DBaaS Aggregator that depend on your case.

## Client libs

You can consume DBaaS Aggregator API in your service via client libraries.
There is DBaaS client libraries based on different languages and frameworks:

- Spring: https://github.com/Netcracker/qubership-dbaas-client/tree/main/dbaas-client-java
- Quarkus: https://github.com/Netcracker/qubership-core-quarkus-extensions/tree/main/dbaas-client
- Golang:
    - https://github.com/Netcracker/qubership-core-lib-go-dbaas-postgres-client
    - https://github.com/Netcracker/qubership-core-lib-go-dbaas-mongo-client
    - https://github.com/Netcracker/qubership-core-lib-go-dbaas-opensearch-client
    - https://github.com/Netcracker/qubership-core-lib-go-dbaas-clickhouse-client
    - https://github.com/Netcracker/qubership-core-lib-go-dbaas-cassandra-client
    - https://github.com/Netcracker/qubership-core-lib-go-dbaas-arangodb-client

Each module contains readme where is described how to use a library.

## Manual operations

Please refer to [rest-api.md](../rest-api.md) spec to get list of available operations.
