# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

FROM --platform=$BUILDPLATFORM golang:1.26@sha256:9d2f36f06329b2a141b9db99ffa32765cf695ee57b813ca29e245e8670bcbfff AS builder
ARG TARGETOS
ARG TARGETARCH
ENV GOTOOLCHAIN=auto

WORKDIR /app
COPY go.mod ./
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-proxy .

FROM ghcr.io/netcracker/qubership-core-base:2.4.2@sha256:78e39e1332035b539eb61f605a9a3b638ccad27b3c64ed0783f76b6b6d4de8c5
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-proxy /app/dbaas-proxy
USER 10001:10001
EXPOSE 8080
CMD ["/app/dbaas-proxy"]
