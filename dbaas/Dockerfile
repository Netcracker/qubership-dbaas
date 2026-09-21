FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.5.4@sha256:8e8e73fd67dfe5f27cbe50afc0a9e328d89b1ea0096c795e74ac69997d837e9a
LABEL maintainer="qubership"

ARG BASE_PATH=.

COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/lib/* /app/lib/
COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/dbaas-aggregator-*-runner.jar /app/dbaas-aggregator.jar
EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["java", "-Xmx512m", "-Dlog.level=INFO", "-jar", "/app/dbaas-aggregator.jar"]
