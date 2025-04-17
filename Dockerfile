FROM ghcr.io/netcracker/qubership/java-base:1.0.0
MAINTAINER qubership

COPY --chown=10001:0 dbaas-aggregator/target/lib/* /app/lib/
COPY --chown=10001:0 dbaas-aggregator/target/dbaas-aggregator-*-runner.jar /app/dbaas-aggregator.jar
EXPOSE 8080

WORKDIR /app

USER 10001:10001