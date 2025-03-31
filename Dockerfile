FROM ghcr.io/netcracker/qubership/java-base:main-20250330180504-10
MAINTAINER qubership

COPY --chown=10001:0 dbaas-aggregator/target/lib/* /app/lib/
ADD --chown=10001:0 dbaas-aggregator/target/dbaas-aggregator-2.0.0-SNAPSHOT-runner.jar /app/dbaas-aggregator-2.0.0-SNAPSHOT.jar
EXPOSE 8080

WORKDIR /app

USER 10001:10001