FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.5.9@sha256:031955af2e2db813fb9a2ccfae47844aad864c07d0677a58abdcff4f380a513a
LABEL maintainer="qubership"

ARG BASE_PATH=.

COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/lib/* /app/lib/
COPY --chown=10001:0 $BASE_PATH/dbaas-aggregator/target/dbaas-aggregator-*-runner.jar /app/dbaas-aggregator.jar
EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["java", "-Xmx512m", "-Dlog.level=INFO", "-jar", "/app/dbaas-aggregator.jar"]
