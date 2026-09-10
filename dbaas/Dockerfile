FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.4.3@sha256:c52f5a22f8b4ce80b5ea44a380924b1fb292abcf4d99aaf4a523aaf2645af398
LABEL maintainer="qubership"

ARG BASE_PATH=.

COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/lib/* /app/lib/
COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/dbaas-aggregator-*-runner.jar /app/dbaas-aggregator.jar
EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["java", "-Xmx512m", "-Dlog.level=INFO", "-jar", "/app/dbaas-aggregator.jar"]
