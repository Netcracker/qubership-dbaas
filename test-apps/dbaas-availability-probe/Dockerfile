# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

FROM --platform=$BUILDPLATFORM golang:1.27@sha256:01aa404fa56b604b6f48e90c1d6e2c79fe5cd78f9592f78d80303fbe171cdcc0 AS builder
ARG TARGETOS
ARG TARGETARCH
ENV GOTOOLCHAIN=auto

WORKDIR /app
COPY go.mod ./
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go test ./internal/... && \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-availability-probe ./cmd/probe

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.12@sha256:2eaad09b552eca2d10d65570f66425322235898ac8d7299eddbad8fdfa81b66b
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-availability-probe /app/dbaas-availability-probe
USER 10001:10001
ENTRYPOINT ["/app/dbaas-availability-probe"]
