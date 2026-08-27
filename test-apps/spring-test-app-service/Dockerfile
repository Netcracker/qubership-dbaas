# Same Java base image as dbaas-aggregator (see dbaas/Dockerfile). The jar is built by the CI
# Maven step (which carries GitHub Packages credentials for the dbaas-client snapshot) and copied
# in here.
FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.4.1@sha256:c33b9a91364471981f577ce52410c6d376bd3b503276b9612294a0450fe55d51
LABEL maintainer="qubership"

WORKDIR /app
COPY --chown=10001:0 --chmod=555 target/spring-test-app-service.jar /app/spring-test-app-service.jar

EXPOSE 8080
USER 10001:10001
CMD ["java", "-jar", "/app/spring-test-app-service.jar"]
