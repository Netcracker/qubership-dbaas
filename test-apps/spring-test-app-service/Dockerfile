# Same Java base image as dbaas-aggregator (see dbaas/Dockerfile). The jar is built by the CI
# Maven step (which carries GitHub Packages credentials for the dbaas-client snapshot) and copied
# in here.
FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.5.4@sha256:8e8e73fd67dfe5f27cbe50afc0a9e328d89b1ea0096c795e74ac69997d837e9a
LABEL maintainer="qubership"

WORKDIR /app
COPY --chown=10001:0 --chmod=555 target/spring-test-app-service.jar /app/spring-test-app-service.jar

EXPOSE 8080
USER 10001:10001
CMD ["java", "-jar", "/app/spring-test-app-service.jar"]
