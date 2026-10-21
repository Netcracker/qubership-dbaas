# Same Java base image as dbaas-aggregator (see dbaas/Dockerfile). The jar is built by the CI
# Maven step (which carries GitHub Packages credentials for the dbaas-client snapshot) and copied
# in here.
FROM ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.5.8@sha256:6ba8a7197e719d56232ef792d644e7623f253c105fa7e817dbbfb924cf47e904
LABEL maintainer="qubership"

WORKDIR /app
COPY --chown=10001:0 --chmod=555 target/spring-test-app-service.jar /app/spring-test-app-service.jar

EXPOSE 8080
USER 10001:10001
CMD ["java", "-jar", "/app/spring-test-app-service.jar"]
