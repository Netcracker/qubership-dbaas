# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

FROM --platform=$BUILDPLATFORM golang:1.27@sha256:2920bb124f1a2e12a1d39f0cab756ae0068441ee1da7655dec7f5e374944095b AS builder
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

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.10@sha256:51173b982248bb866b236f504579b71c0a9c4a41975b957595f9faa9ab3fb5d6
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-availability-probe /app/dbaas-availability-probe
USER 10001:10001
ENTRYPOINT ["/app/dbaas-availability-probe"]
