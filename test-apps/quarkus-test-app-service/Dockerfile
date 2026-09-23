# Same Java base image as dbaas-aggregator (see dbaas/Dockerfile). The Quarkus fast-jar is built by
# the CI Maven step (which carries GitHub Packages credentials for the dbaas-quarkus snapshot) and
# copied in here.
FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.5.5@sha256:555b0ea473473480cd131f8e9dd560d8c64664c1fb794d039da5c584df6adeb4
LABEL maintainer="qubership"

WORKDIR /app
# Quarkus fast-jar layout (target/quarkus-app): lib first (changes least), then the rest.
COPY --chown=10001:0 --chmod=555 target/quarkus-app/lib/ /app/lib/
COPY --chown=10001:0 --chmod=555 target/quarkus-app/*.jar /app/
COPY --chown=10001:0 --chmod=555 target/quarkus-app/app/ /app/app/
COPY --chown=10001:0 --chmod=555 target/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8080
USER 10001:10001
CMD ["java", "-jar", "/app/quarkus-run.jar"]
