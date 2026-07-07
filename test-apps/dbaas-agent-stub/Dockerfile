# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM golang:1.26 AS builder
ARG TARGETOS
ARG TARGETARCH
ENV GOTOOLCHAIN=auto

WORKDIR /app
COPY go.mod ./
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-agent-stub .

FROM ghcr.io/netcracker/qubership-core-base:2.3.4@sha256:55b2de47ca9fd85127a2077c65b9187f04ffd41849b2460f49c71b7e6372f940
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-agent-stub /app/dbaas-agent-stub
USER 10001:10001
EXPOSE 8080
CMD ["/app/dbaas-agent-stub"]
