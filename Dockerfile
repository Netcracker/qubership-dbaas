FROM ghcr.io/netcracker/qubership-core.java-alpine.amd64:dev-docker-publish
MAINTAINER qubership

COPY --chown=10001:0 dbaas-aggregator/target/lib/* /app/lib/
ADD --chown=10001:0 dbaas-aggregator/target/dbaas-aggregator-2.0.0-SNAPSHOT-runner.jar /app/dbaas-aggregator-2.0.0-SNAPSHOT.jar
EXPOSE 8080

CMD ["java", "-Xmx512m", "-Dlog.level=INFO", "-jar", "/app/dbaas-aggregator-2.0.0-SNAPSHOT.jar"]

WORKDIR /app

USER 10001:10001