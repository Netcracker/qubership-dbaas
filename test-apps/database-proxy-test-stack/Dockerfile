# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

# DBaaS Proxy runtime image for the rotation test fixture.
#
# The binary is built outside this Dockerfile, by build-image.sh, because dbaas-proxy depends on Go
# modules hosted on the internal GitLab that a container build cannot reach. Upstream builds it the
# same way. This stage only packages the result on the public platform base image, so the fixture
# image pulls nothing from an internal registry.
FROM ghcr.io/netcracker/qubership-core-base:2.3.7@sha256:b917b3a1731a2ae26b507d22565f030ec25ff8d28b75a80b8b08bbc946f4d73b
WORKDIR /app
COPY --chown=10001:0 --chmod=555 dbaas-proxy /app/dbaas-proxy
COPY --chown=10001:0 --chmod=444 application.yaml /app/application.yaml
USER 10001:10001
EXPOSE 8080
CMD ["/app/dbaas-proxy"]
