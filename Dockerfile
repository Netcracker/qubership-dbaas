FROM ghcr.io/netcracker/qubership/java-base:1.0.0
MAINTAINER qubership

ARG BASE_PATH=.

COPY --chown=10001:0 $BASE_PATH/lib/* /app/lib/
COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator-*-runner.jar /app/dbaas-aggregator.jar
EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["java", "-Xmx512m", "-Dlog.level=INFO", "-jar", "/app/dbaas-aggregator.jar"]