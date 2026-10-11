# Same Java base image as dbaas-aggregator (see dbaas/Dockerfile). The jar is built by the CI
# Maven step (which carries GitHub Packages credentials for the dbaas-client snapshot) and copied
# in here.
FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.5.7@sha256:95a98a73eca18f351249b54c646e2cd920030d935ef2fb5c6ce198048aaeefce
LABEL maintainer="qubership"

WORKDIR /app
COPY --chown=10001:0 --chmod=555 target/spring-test-app-service.jar /app/spring-test-app-service.jar

EXPOSE 8080
USER 10001:10001
CMD ["java", "-jar", "/app/spring-test-app-service.jar"]
