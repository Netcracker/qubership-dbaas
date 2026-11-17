# Same Java base image as dbaas-aggregator (see dbaas/Dockerfile). The jar is built by the CI
# Maven step (which carries GitHub Packages credentials for the dbaas-client snapshot) and copied
# in here.
FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.5.13@sha256:1da226993cb0c0fa213477fe393d6495f89702f8dfb4c2a44ad53e369d426d7c
LABEL maintainer="qubership"

WORKDIR /app
COPY --chown=10001:0 --chmod=555 target/spring-test-app-service.jar /app/spring-test-app-service.jar

EXPOSE 8080
USER 10001:10001
CMD ["java", "-jar", "/app/spring-test-app-service.jar"]
