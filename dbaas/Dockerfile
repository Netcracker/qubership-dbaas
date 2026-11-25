FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.6.1@sha256:2b876eeb2497abffd5eb2228bf44c71bfe119a97030dbd9c1858c782b4585abe
LABEL maintainer="qubership"

ARG BASE_PATH=.

COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/lib/* /app/lib/
COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/dbaas-aggregator-*-runner.jar /app/dbaas-aggregator.jar
EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["java", "-Xmx512m", "-Dlog.level=INFO", "-jar", "/app/dbaas-aggregator.jar"]
