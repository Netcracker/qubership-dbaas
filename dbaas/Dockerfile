FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.4.1@sha256:c33b9a91364471981f577ce52410c6d376bd3b503276b9612294a0450fe55d51
LABEL maintainer="qubership"

ARG BASE_PATH=.

COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/lib/* /app/lib/
COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/dbaas-aggregator-*-runner.jar /app/dbaas-aggregator.jar
EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["java", "-Xmx512m", "-Dlog.level=INFO", "-jar", "/app/dbaas-aggregator.jar"]
