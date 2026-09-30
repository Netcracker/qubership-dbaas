# Same Java base image as dbaas-aggregator (see dbaas/Dockerfile). The Quarkus fast-jar is built by
# the CI Maven step (which carries GitHub Packages credentials for the dbaas-quarkus snapshot) and
# copied in here.
FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.5.6@sha256:8c7a4ee75b86c68343709b69c8f3fc6efdcb2be1302ff2e7c8441910bc5bae45
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
